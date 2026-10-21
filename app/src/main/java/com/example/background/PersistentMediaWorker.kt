package com.example.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.R
import com.example.audio.AudioTranscoder
import com.example.audio.VideoTranscoder
import com.example.data.MediaItemRepository
import com.example.downloader.MediaDownloader
import com.example.model.AudioFormat
import com.example.model.DuplicateAction
import com.example.model.FileType
import com.example.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Executes durable download/conversion work in WorkManager's foreground service. */
class PersistentMediaWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    private val repository = MediaItemRepository(applicationContext)
    private val audioTranscoder = AudioTranscoder(applicationContext)
    private val videoTranscoder = VideoTranscoder(applicationContext)
    private val downloader = MediaDownloader(applicationContext, audioTranscoder)

    override suspend fun doWork(): Result {
        val taskFilePath = inputData.getString(KEY_TASK_FILE)
            ?: return failure("ملف بيانات المهمة غير موجود.")
        val taskFile = File(taskFilePath)
        val taskRoot = File(applicationContext.filesDir, "media_work/tasks").canonicalFile
        if (!taskFile.canonicalFile.path.startsWith(taskRoot.path + File.separator)) {
            return failure("مسار بيانات المهمة غير صالح.")
        }

        val task = try {
            BackgroundMediaTaskStore.read(taskFile)
        } catch (error: Exception) {
            taskFile.delete()
            return failure(error.localizedMessage ?: "تعذر قراءة بيانات المهمة المحفوظة.")
        }

        if (inputData.getBoolean(KEY_RESUME, false)) {
            BackgroundMediaTaskStore.clearPaused(taskFile)
        }
        val reporter = ProgressReporter(task, CoroutineScope(currentCoroutineContext()))
        try {
            reporter.publishCurrent()
            return when (task) {
                is StoredMediaTask.DownloadBatch -> runDownloadBatch(task, reporter)
                is StoredMediaTask.LocalConversion -> runLocalConversion(task, reporter)
            }
        } catch (cancelled: CancellationException) {
            // Keep the durable descriptor on every stop: a pause keeps it for resume, while
            // WorkManager may also stop/retry a long-running job for system scheduling reasons.
            // Explicit cancellation cleanup is performed by the ViewModel once WorkInfo is terminal.
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount < MAX_AUTOMATIC_RETRIES && !isStopped) {
                return Result.retry()
            }
            val hasSavedLocalOutput = task is StoredMediaTask.LocalConversion &&
                repository.itemsFlow.value.any { it.id == task.id && File(it.filePath).isFile }
            cleanupTask(
                taskFile,
                task,
                deleteOutput = task is StoredMediaTask.LocalConversion && !hasSavedLocalOutput
            )
            return failure(error.localizedMessage ?: "توقفت المهمة بسبب خطأ غير متوقع.")
        } finally {
            reporter.flushIfActive()
        }
    }

    private suspend fun runDownloadBatch(
        task: StoredMediaTask.DownloadBatch,
        reporter: ProgressReporter
    ): Result {
        var succeeded = 0
        var failed = 0
        val completedIds = mutableListOf<String>()
        var lastFailure: String? = null
        val total = task.requests.size

        for ((index, request) in task.requests.withIndex()) {
            ensureActive()
            val item = request.item
            val existing = repository.itemsFlow.value.firstOrNull { it.id == request.mediaItemId }
            if (existing != null && File(existing.filePath).isFile && File(existing.filePath).length() > 0L) {
                downloader.cleanupResumableDownload(request.mediaItemId)
                succeeded++
                completedIds += existing.id
                continue
            }

            reporter.update(
                progress = 0f,
                itemTitle = item.title,
                stage = "تحميل الملف (${index + 1}/$total)…",
                currentIndex = index + 1,
                totalCount = total,
                progressKind = PROGRESS_DOWNLOAD,
                force = true
            )
            val result = downloader.downloadAndProcessItem(
                item = item,
                targetFormat = task.format,
                targetSpeed = task.speed,
                targetQuality = task.quality,
                targetBitrate = task.bitrate,
                // The planned file name is owned by this work item. Replacing that exact file
                // makes WorkManager retries idempotent without choosing a new suffix.
                duplicateAction = DuplicateAction.REPLACE_EXISTING,
                outputFileName = request.outputFileName,
                resumeKey = request.mediaItemId,
                preservePartialOnCancellation = true,
                onDownloadProgress = { progress, downloaded, totalBytes ->
                    reporter.update(
                        progress = progress * DOWNLOAD_PROGRESS_SHARE,
                        itemTitle = item.title,
                        stage = "تحميل الملف (${index + 1}/$total)…",
                        currentIndex = index + 1,
                        totalCount = total,
                        bytesDownloaded = downloaded,
                        totalBytes = totalBytes,
                        progressKind = PROGRESS_DOWNLOAD
                    )
                },
                onSpeedProgress = { progress, stage ->
                    reporter.update(
                        progress = DOWNLOAD_PROGRESS_SHARE + progress * (1f - DOWNLOAD_PROGRESS_SHARE),
                        itemTitle = item.title,
                        stage = stage,
                        currentIndex = index + 1,
                        totalCount = total,
                        progressKind = PROGRESS_PROCESSING
                    )
                }
            )
            ensureActive()

            if (result == null) {
                failed++
                lastFailure = "تعذر تحميل أو تحويل ${item.title}."
                continue
            }
            val savedItem = result.copy(id = request.mediaItemId)
            if (repository.addItem(savedItem)) {
                downloader.cleanupResumableDownload(request.mediaItemId)
                succeeded++
                completedIds += savedItem.id
            } else {
                File(savedItem.filePath).delete()
                failed++
                lastFailure = repository.storageError.value ?: "تعذر حفظ الملف في سجل المكتبة."
            }
        }

        // This worker reached a terminal result. Partial sources are retained only while
        // WorkManager may resume a cancelled/stopped execution.
        task.requests.forEach { downloader.cleanupResumableDownload(it.mediaItemId) }

        val message = when {
            succeeded == 0 -> lastFailure ?: "فشل تحميل جميع العناصر المحددة."
            failed == 0 -> "اكتمل تحميل $succeeded ملفًا بنجاح."
            else -> "اكتمل تحميل $succeeded ملفًا، وتعذر تحميل $failed عنصرًا."
        }
        val output = workDataOf(
            KEY_RESULT_TYPE to TYPE_DOWNLOADS,
            KEY_RESULT_TITLE to task.title,
            KEY_RESULT_COMPLETED_IDS to completedIds.toTypedArray(),
            KEY_RESULT_FAILED_COUNT to failed,
            KEY_RESULT_MESSAGE to message
        )
        return if (succeeded == 0) {
            cleanupTask(inputTaskFile(), task)
            Result.failure(output)
        } else {
            Result.success(output)
        }
    }

    private suspend fun runLocalConversion(
        originalTask: StoredMediaTask.LocalConversion,
        reporter: ProgressReporter
    ): Result {
        var task = originalTask
        val existingItem = repository.itemsFlow.value.firstOrNull { it.id == task.id }
        if (existingItem != null && File(existingItem.filePath).isFile && File(existingItem.filePath).length() > 0L) {
            return Result.success(
                workDataOf(
                    KEY_RESULT_TYPE to TYPE_LOCAL,
                    KEY_RESULT_TITLE to task.title,
                    KEY_RESULT_ITEM_ID to existingItem.id,
                    KEY_RESULT_MESSAGE to "تم استعادة نتيجة التحويل المحفوظة بعد إعادة تشغيل المهمة."
                )
            )
        }
        val outputFile = File(task.outputPath)
        val sourceDirectory = File(applicationContext.filesDir, "media_work/sources")
        val durableSource = task.sourcePath.takeIf { it.isNotBlank() }?.let { File(it) }
            ?.takeIf { it.isFile && it.length() > 0L }
            ?: File(sourceDirectory, "${task.id}.source").takeIf { it.isFile && it.length() > 0L }
        val sourceFile = durableSource ?: BackgroundMediaTaskStore.stageLocalSource(
            context = applicationContext,
            uri = Uri.parse(task.sourceUri),
            taskId = task.id,
            onProgress = { copied, total ->
                val copyProgress = if (total > 0L) (copied.toFloat() / total).coerceIn(0f, 1f) else 0f
                reporter.update(
                    progress = copyProgress * 0.08f,
                    itemTitle = task.title,
                    stage = "نسخ المصدر إلى مساحة العمل المستمرة…",
                    progressKind = PROGRESS_PROCESSING
                )
            }
        )
        if (task.sourcePath != sourceFile.absolutePath) {
            task = task.copy(sourcePath = sourceFile.absolutePath)
            BackgroundMediaTaskStore.write(applicationContext, task)
        }
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            cleanupTask(inputTaskFile(), task, deleteOutput = true)
            return failure("تعذر استعادة ملف المصدر؛ أُلغيت عملية التحويل.")
        }

        val isVideoOutput = task.format in setOf(AudioFormat.MP4, AudioFormat.MKV, AudioFormat.WEBM)
        if (isVideoOutput && !task.isVideoSource) {
            cleanupTask(inputTaskFile(), task, deleteOutput = true)
            return failure("صيغة الفيديو تتطلب مصدرًا يحتوي على مسار فيديو.")
        }

        var audioOutputInfo: AudioTranscoder.AudioOutputInfo? = null
        var resourceLimitFailure: String? = null
        val succeeded: Boolean
        var outputResolution = ""
        var outputDurationMs = 0L
        var outputBitrateKbps = task.bitrate.kbps

        reporter.update(0.08f, task.title, "بدء تحويل الملف…", progressKind = PROGRESS_PROCESSING, force = true)
        if (isVideoOutput) {
            val videoInfo = videoTranscoder.transcodeVideo(
                sourceFile = sourceFile,
                outputFile = outputFile,
                targetQuality = task.quality,
                targetSpeed = task.speed,
                onProgress = { progress, stage ->
                    reporter.update(0.08f + progress.coerceIn(0f, 1f) * 0.92f, task.title, stage, progressKind = PROGRESS_PROCESSING)
                }
            )
            succeeded = videoInfo != null && outputFile.isFile && outputFile.length() > 0L
            if (videoInfo != null) {
                outputResolution = "${minOf(videoInfo.width, videoInfo.height)}p (${videoInfo.width} × ${videoInfo.height})"
                outputDurationMs = videoInfo.durationUs / 1000L
                outputBitrateKbps = videoInfo.bitRate.takeIf { it > 0 }?.div(1000) ?: 0
            }
        } else {
            succeeded = audioTranscoder.transcodeAudio(
                sourceUri = null,
                sourceFile = sourceFile,
                outputFile = outputFile,
                targetSpeed = task.speed,
                targetFormat = task.format,
                bitrateKbps = task.bitrate.kbps,
                onProgress = { progress, stage ->
                    if (stage.startsWith("توقف التحويل بأمان:")) resourceLimitFailure = stage
                    reporter.update(0.08f + progress.coerceIn(0f, 1f) * 0.92f, task.title, stage, progressKind = PROGRESS_PROCESSING)
                },
                onOutputInfo = { audioOutputInfo = it }
            ) && outputFile.isFile && outputFile.length() > 0L
            outputBitrateKbps = audioOutputInfo?.appliedBitrateKbps ?: task.bitrate.kbps
            outputDurationMs = if (task.originalDurationMs > 0L && task.speed > 0f) {
                (task.originalDurationMs / task.speed).toLong()
            } else 0L
        }

        ensureActive()
        if (!succeeded) {
            val message = resourceLimitFailure
                ?: "فشلت عملية التحويل. تحقق من دعم الجهاز للصيغة المطلوبة ثم أعد المحاولة؛ لم يُحفظ ملف جزئي."
            cleanupTask(inputTaskFile(), task, deleteOutput = true)
            return failure(message)
        }

        val item = MediaItem(
            id = task.id,
            title = if (isVideoOutput) {
                "${task.title} (${outputResolution.substringAfter('(').substringBefore(')')}, ${task.speed}x)"
            } else {
                "${task.title} (${task.speed}x, ${outputBitrateKbps} kbps)"
            },
            filePath = outputFile.absolutePath,
            durationMs = outputDurationMs,
            originalDurationMs = task.originalDurationMs,
            speed = task.speed,
            format = task.format.name,
            fileSize = outputFile.length(),
            isVideo = isVideoOutput,
            fileType = if (isVideoOutput) FileType.VIDEO else FileType.AUDIO,
            resolutionText = outputResolution,
            bitrateKbps = outputBitrateKbps,
            sourceUrl = task.sourceUri
        )
        if (!repository.addItem(item)) {
            val message = repository.storageError.value ?: "تعذر حفظ الناتج في سجل المكتبة."
            cleanupTask(inputTaskFile(), task, deleteOutput = true)
            return failure(message)
        }

        val message = if (isVideoOutput) {
            "أُعيد ترميز الفيديو فعليًا إلى ${outputResolution.substringAfter('(').substringBefore(')')} بسرعة ${task.speed}x داخل ${task.format.extension.uppercase()}."
        } else {
            "تم تحويل الملف بنجاح بصيغة ${task.format.name} بمعدل ${outputBitrateKbps} kbps."
        }
        return Result.success(
            workDataOf(
                KEY_RESULT_TYPE to TYPE_LOCAL,
                KEY_RESULT_TITLE to task.title,
                KEY_RESULT_ITEM_ID to item.id,
                KEY_RESULT_MESSAGE to message
            )
        )
    }

    private fun cleanupTask(taskFile: File, task: StoredMediaTask?, deleteOutput: Boolean = false) {
        BackgroundMediaTaskStore.delete(taskFile, task, deleteOutput)
    }

    private fun inputTaskFile(): File = File(requireNotNull(inputData.getString(KEY_TASK_FILE)))

    private suspend fun ensureActive() {
        if (!currentCoroutineContext().isActive || isStopped) throw CancellationException("Work was cancelled")
    }

    private fun failure(message: String): Result = Result.failure(
        workDataOf(KEY_RESULT_MESSAGE to message, KEY_RESULT_TYPE to inputData.getString(KEY_TASK_TYPE).orEmpty())
    )

    private inner class ProgressReporter(
        task: StoredMediaTask,
        private val scope: CoroutineScope
    ) {
        private val lock = Any()
        private var latest = ProgressSnapshot(
            progress = 0f,
            itemTitle = task.title,
            stage = "جارٍ تجهيز المهمة…",
            currentIndex = 1,
            totalCount = if (task is StoredMediaTask.DownloadBatch) task.requests.size else 1,
            bytesDownloaded = 0L,
            totalBytes = 0L,
            kind = if (task is StoredMediaTask.DownloadBatch) PROGRESS_DOWNLOAD else PROGRESS_PROCESSING
        )
        private var lastPublishedAt = 0L
        private var publishJob: Job? = null

        suspend fun publishCurrent() {
            val snapshot = synchronized(lock) { latest }
            publish(snapshot)
        }

        fun update(
            progress: Float,
            itemTitle: String,
            stage: String,
            currentIndex: Int = 1,
            totalCount: Int = 1,
            bytesDownloaded: Long = 0L,
            totalBytes: Long = 0L,
            progressKind: String = PROGRESS_PROCESSING,
            force: Boolean = false
        ) {
            synchronized(lock) {
                latest = ProgressSnapshot(
                    progress = progress.coerceIn(0f, 1f),
                    itemTitle = itemTitle.take(MAX_TEXT_LENGTH),
                    stage = stage.take(MAX_TEXT_LENGTH),
                    currentIndex = currentIndex,
                    totalCount = totalCount,
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    kind = progressKind
                )
                val now = android.os.SystemClock.elapsedRealtime()
                if (!force && now - lastPublishedAt < PROGRESS_UPDATE_INTERVAL_MS) return
                if (publishJob?.isActive == true) return
                lastPublishedAt = now
                publishJob = scope.launch { publish(latest) }
            }
        }

        suspend fun flushIfActive() {
            publishJob?.join()
        }

        private suspend fun publish(snapshot: ProgressSnapshot) {
            val progressData = workDataOf(
                KEY_PROGRESS to snapshot.progress,
                KEY_ITEM_TITLE to snapshot.itemTitle,
                KEY_STAGE to snapshot.stage,
                KEY_CURRENT_INDEX to snapshot.currentIndex,
                KEY_TOTAL_COUNT to snapshot.totalCount,
                KEY_BYTES_DOWNLOADED to snapshot.bytesDownloaded,
                KEY_TOTAL_BYTES to snapshot.totalBytes,
                KEY_PROGRESS_KIND to snapshot.kind
            )
            setProgress(progressData)
            setForeground(createForegroundInfo(snapshot))
        }
    }

    private data class ProgressSnapshot(
        val progress: Float,
        val itemTitle: String,
        val stage: String,
        val currentIndex: Int,
        val totalCount: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val kind: String
    )

    private fun createForegroundInfo(snapshot: ProgressSnapshot): ForegroundInfo {
        createNotificationChannel()
        val notificationId = NOTIFICATION_ID_BASE + (id.hashCode() and 0x0fff)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(snapshot.itemTitle.take(MAX_TEXT_LENGTH))
            .setContentText(snapshot.stage.take(MAX_TEXT_LENGTH))
            .setStyle(NotificationCompat.BigTextStyle().bigText(snapshot.stage))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (snapshot.progress * 100).toInt(), snapshot.totalBytes <= 0L && snapshot.progress == 0f)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إلغاء",
                androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            )
            .build()
        val serviceType = when {
            Build.VERSION.SDK_INT >= 35 && inputData.getString(KEY_TASK_TYPE) == TYPE_LOCAL ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, serviceType)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "مهام الوسائط",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "إشعارات التحميل والتحويل المستمر في الخلفية" }
                )
            }
        }
    }

    companion object {
        const val TAG = "file-station-persistent-media"
        const val TAG_TYPE_PREFIX = "file-station-task-type:"
        const val TAG_TITLE_PREFIX = "file-station-task-title:"
        const val TAG_CREATED_AT_PREFIX = "file-station-task-created:"
        const val TAG_TOTAL_COUNT_PREFIX = "file-station-task-count:"
        const val TAG_SPEED_PREFIX = "file-station-task-speed:"
        const val TAG_TASK_FILE_PREFIX = "file-station-task-file:"
        const val UNIQUE_WORK_NAME = "file-station-persistent-media-chain"
        const val KEY_TASK_FILE = "task_file"
        const val KEY_TASK_TYPE = "task_type"
        const val KEY_TASK_TITLE = "task_title"
        const val KEY_TASK_CREATED_AT = "task_created_at"
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_SPEED = "speed"
        const val KEY_RESUME = "resume"

        const val KEY_PROGRESS = "progress"
        const val KEY_ITEM_TITLE = "item_title"
        const val KEY_STAGE = "stage"
        const val KEY_CURRENT_INDEX = "current_index"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_PROGRESS_KIND = "progress_kind"

        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_RESULT_TITLE = "result_title"
        const val KEY_RESULT_COMPLETED_IDS = "completed_ids"
        const val KEY_RESULT_FAILED_COUNT = "failed_count"
        const val KEY_RESULT_ITEM_ID = "item_id"
        const val KEY_RESULT_MESSAGE = "result_message"

        const val TYPE_DOWNLOADS = "downloads"
        const val TYPE_LOCAL = "local_conversion"
        const val PROGRESS_DOWNLOAD = "download"
        const val PROGRESS_PROCESSING = "processing"
        private const val DOWNLOAD_PROGRESS_SHARE = 0.7f
        private const val MAX_AUTOMATIC_RETRIES = 2
        private const val MAX_TEXT_LENGTH = 180
        private const val PROGRESS_UPDATE_INTERVAL_MS = 400L
        private const val NOTIFICATION_CHANNEL_ID = "persistent_media_tasks"
        private const val NOTIFICATION_ID_BASE = 4100
    }
}
