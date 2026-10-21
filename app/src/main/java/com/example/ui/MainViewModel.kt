package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.audio.AudioPlayerController
import com.example.audio.AudioTranscoder
import com.example.audio.VideoTranscoder
import com.example.background.BackgroundMediaTaskStore
import com.example.background.DownloadRequest
import com.example.background.PersistentMediaWorker
import com.example.background.StoredMediaTask
import com.example.data.MediaItemRepository
import com.example.downloader.DirectDownloadMetadataResolver
import com.example.downloader.DownloadFilePlan
import com.example.downloader.DownloadFilePlanner
import com.example.downloader.MediaDownloader
import com.example.model.AudioBitrate
import com.example.model.AudioFormat
import com.example.model.DownloadState
import com.example.model.DuplicateAction
import com.example.model.FileType
import com.example.model.MediaItem
import com.example.model.PlaylistItem
import com.example.model.ProcessingState
import com.example.model.VideoQuality
import com.example.ui.screens.SearchEngine
import com.example.ui.screens.SearchResultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import java.util.regex.Pattern

data class DuplicateConflict(
    val fileName: String,
    val itemTitle: String,
    val conflictIndex: Int,
    val conflictCount: Int,
    val pendingAction: (DuplicateAction) -> Unit
)

private data class PlannedDownloadRequest(
    val item: PlaylistItem,
    val filePlan: DownloadFilePlan,
    val duplicateAction: DuplicateAction = DuplicateAction.DOWNLOAD_ANYWAY
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val transcoder = AudioTranscoder(application)
    private val videoTranscoder = VideoTranscoder(application)
    val downloader = MediaDownloader(application, transcoder)
    val repository = MediaItemRepository(application)
    val player = AudioPlayerController(application)
    private val workManager = WorkManager.getInstance(application)
    private var enqueueInProgress = false
    private val _backgroundWorkPaused = MutableStateFlow(false)
    val backgroundWorkPaused = _backgroundWorkPaused.asStateFlow()

    private val httpClient = OkHttpClient()

    val libraryItems: StateFlow<List<MediaItem>> = repository.itemsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val playerState = player.playerState

    // Downloader tab state
    private val _downloadUrl = MutableStateFlow("")
    val downloadUrl = _downloadUrl.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    private val _targetDownloadFormat = MutableStateFlow(AudioFormat.MP4) // Default video format
    val targetDownloadFormat = _targetDownloadFormat.asStateFlow()

    private val _targetDownloadSpeed = MutableStateFlow(1.0f)
    val targetDownloadSpeed = _targetDownloadSpeed.asStateFlow()

    private val _targetDownloadQuality = MutableStateFlow(VideoQuality.Q720P)
    val targetDownloadQuality = _targetDownloadQuality.asStateFlow()

    private val _targetDownloadBitrate = MutableStateFlow(AudioBitrate.B128)
    val targetDownloadBitrate = _targetDownloadBitrate.asStateFlow()

    // Conflict Dialog state
    private val _duplicateConflict = MutableStateFlow<DuplicateConflict?>(null)
    val duplicateConflict = _duplicateConflict.asStateFlow()

    // Local file speed changer & converter state
    private val _selectedLocalUri = MutableStateFlow<Uri?>(null)
    val selectedLocalUri = _selectedLocalUri.asStateFlow()
    private val _selectedUriPersistable = MutableStateFlow(false)

    private val _selectedLocalMetadata = MutableStateFlow<MediaItem?>(null)
    val selectedLocalMetadata = _selectedLocalMetadata.asStateFlow()

    private val _targetLocalSpeed = MutableStateFlow(1.0f)
    val targetLocalSpeed = _targetLocalSpeed.asStateFlow()

    private val _targetLocalQuality = MutableStateFlow(VideoQuality.Q720P)
    val targetLocalQuality = _targetLocalQuality.asStateFlow()

    private val _targetLocalFormat = MutableStateFlow(AudioFormat.MP3)
    val targetLocalFormat = _targetLocalFormat.asStateFlow()

    private val _targetLocalBitrate = MutableStateFlow(AudioBitrate.B128)
    val targetLocalBitrate = _targetLocalBitrate.asStateFlow()

    private val _conversionState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val conversionState = _conversionState.asStateFlow()

    // Web Search state
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()

    init {
        repository.storageError.value?.let { _actionMessage.value = it }
        observePersistentWork()
    }

    @Synchronized
    private fun reserveBackgroundEnqueue(): Boolean {
        if (enqueueInProgress) return false
        enqueueInProgress = true
        return true
    }

    @Synchronized
    private fun releaseBackgroundEnqueue() {
        enqueueInProgress = false
    }

    private suspend fun hasNoActiveBackgroundWork(): Boolean = withContext(Dispatchers.IO) {
        try {
            workManager.getWorkInfosByTag(PersistentMediaWorker.TAG).get().none { info ->
                val taskFile = info.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX)?.let { File(it) }
                !info.state.isFinished ||
                    (info.state == WorkInfo.State.CANCELLED && taskFile != null && BackgroundMediaTaskStore.isPaused(taskFile))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun WorkInfo.taggedValue(prefix: String): String? =
        tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private fun buildPersistentWorkRequest(
        descriptor: File,
        task: StoredMediaTask,
        isResume: Boolean = false
    ) = run {
        val workCreatedAt = if (isResume) System.currentTimeMillis() else task.createdAt
        val builder = OneTimeWorkRequestBuilder<PersistentMediaWorker>()
        if (task is StoredMediaTask.DownloadBatch) {
            builder.setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
        }
        builder.setInputData(
            workDataOf(
                PersistentMediaWorker.KEY_TASK_FILE to descriptor.absolutePath,
                PersistentMediaWorker.KEY_TASK_TYPE to when (task) {
                    is StoredMediaTask.DownloadBatch -> PersistentMediaWorker.TYPE_DOWNLOADS
                    is StoredMediaTask.LocalConversion -> PersistentMediaWorker.TYPE_LOCAL
                },
                PersistentMediaWorker.KEY_TASK_TITLE to task.title,
                PersistentMediaWorker.KEY_TASK_CREATED_AT to workCreatedAt,
                PersistentMediaWorker.KEY_TOTAL_COUNT to if (task is StoredMediaTask.DownloadBatch) task.requests.size else 1,
                PersistentMediaWorker.KEY_SPEED to task.speed,
                PersistentMediaWorker.KEY_RESUME to isResume
            )
        ).addTag(PersistentMediaWorker.TAG)
            .addTag("${PersistentMediaWorker.TAG_TYPE_PREFIX}${if (task is StoredMediaTask.DownloadBatch) PersistentMediaWorker.TYPE_DOWNLOADS else PersistentMediaWorker.TYPE_LOCAL}")
            .addTag("${PersistentMediaWorker.TAG_TITLE_PREFIX}${task.title.take(180)}")
            .addTag("${PersistentMediaWorker.TAG_CREATED_AT_PREFIX}$workCreatedAt")
            .addTag("${PersistentMediaWorker.TAG_TOTAL_COUNT_PREFIX}${if (task is StoredMediaTask.DownloadBatch) task.requests.size else 1}")
            .addTag("${PersistentMediaWorker.TAG_SPEED_PREFIX}${task.speed}")
            .addTag("${PersistentMediaWorker.TAG_TASK_FILE_PREFIX}${descriptor.absolutePath}")
            .build()
    }

    private fun observePersistentWork() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(PersistentMediaWorker.TAG).collect { workInfos ->
                cleanupCompletedWork(workInfos)
                val activeWork = workInfos
                    .filterNot { it.state.isFinished }
                    .minByOrNull {
                        it.taggedValue(PersistentMediaWorker.TAG_CREATED_AT_PREFIX)?.toLongOrNull() ?: Long.MAX_VALUE
                    }
                val workInfo = activeWork ?: workInfos.maxByOrNull {
                    it.taggedValue(PersistentMediaWorker.TAG_CREATED_AT_PREFIX)?.toLongOrNull() ?: 0L
                } ?: return@collect
                renderPersistentWork(workInfo)
            }
        }
    }

    private suspend fun cleanupCompletedWork(workInfos: List<WorkInfo>) = withContext(Dispatchers.IO) {
        workInfos.filter { it.state == WorkInfo.State.SUCCEEDED }.forEach { info ->
            val path = info.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX) ?: return@forEach
            val taskFile = File(path)
            if (taskFile.exists()) {
                val task = runCatching { BackgroundMediaTaskStore.read(taskFile) }.getOrNull()
                BackgroundMediaTaskStore.delete(taskFile, task)
            }
        }
    }

    private suspend fun renderPersistentWork(workInfo: WorkInfo) {
        val type = workInfo.taggedValue(PersistentMediaWorker.TAG_TYPE_PREFIX).orEmpty()
        val title = workInfo.taggedValue(PersistentMediaWorker.TAG_TITLE_PREFIX) ?: "مهمة وسائط"
        val inputCount = workInfo.taggedValue(PersistentMediaWorker.TAG_TOTAL_COUNT_PREFIX)?.toIntOrNull() ?: 1
        val speed = workInfo.taggedValue(PersistentMediaWorker.TAG_SPEED_PREFIX)?.toFloatOrNull() ?: 1f
        val progress = workInfo.progress.getFloat(PersistentMediaWorker.KEY_PROGRESS, 0f).coerceIn(0f, 1f)
        val itemTitle = workInfo.progress.getString(PersistentMediaWorker.KEY_ITEM_TITLE) ?: title
        val stage = workInfo.progress.getString(PersistentMediaWorker.KEY_STAGE) ?: "في قائمة انتظار المهام الخلفية…"

        when (workInfo.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                _backgroundWorkPaused.value = false
                if (type == PersistentMediaWorker.TYPE_LOCAL) {
                    _conversionState.value = ProcessingState.Processing(progress, stage)
                } else {
                    val index = workInfo.progress.getInt(PersistentMediaWorker.KEY_CURRENT_INDEX, 1)
                    val total = workInfo.progress.getInt(PersistentMediaWorker.KEY_TOTAL_COUNT, inputCount)
                    val kind = workInfo.progress.getString(PersistentMediaWorker.KEY_PROGRESS_KIND)
                    _downloadState.value = if (kind == PersistentMediaWorker.PROGRESS_PROCESSING) {
                        DownloadState.ProcessingSpeed(
                            itemTitle = itemTitle,
                            progress = progress,
                            speed = speed
                        )
                    } else {
                        DownloadState.Downloading(
                            currentItemTitle = itemTitle,
                            currentIndex = index.coerceAtLeast(1),
                            totalCount = total.coerceAtLeast(1),
                            progress = progress,
                            bytesDownloaded = workInfo.progress.getLong(PersistentMediaWorker.KEY_BYTES_DOWNLOADED, 0L),
                            totalBytes = workInfo.progress.getLong(PersistentMediaWorker.KEY_TOTAL_BYTES, 0L)
                        )
                    }
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                _backgroundWorkPaused.value = false
                val message = workInfo.outputData.getString(PersistentMediaWorker.KEY_RESULT_MESSAGE).orEmpty()
                if (type == PersistentMediaWorker.TYPE_LOCAL) {
                    repository.refreshFromDisk()
                    val itemId = workInfo.outputData.getString(PersistentMediaWorker.KEY_RESULT_ITEM_ID)
                    val item = repository.itemsFlow.value.firstOrNull { it.id == itemId }
                    _conversionState.value = if (item != null) ProcessingState.Success(item, message)
                    else ProcessingState.Error("اكتمل التحويل لكن تعذر استعادة عنصر المكتبة.")
                    _actionMessage.value = message
                } else {
                    repository.refreshFromDisk()
                    val completedIds = workInfo.outputData.getStringArray(PersistentMediaWorker.KEY_RESULT_COMPLETED_IDS)
                        ?.toSet().orEmpty()
                    val completedItems = repository.itemsFlow.value.filter { it.id in completedIds }
                    val failedCount = workInfo.outputData.getInt(PersistentMediaWorker.KEY_RESULT_FAILED_COUNT, 0)
                    _downloadState.value = if (completedItems.isEmpty()) DownloadState.Error(message)
                    else DownloadState.Completed(completedItems, failedCount)
                    _actionMessage.value = message
                }
            }
            WorkInfo.State.FAILED -> {
                _backgroundWorkPaused.value = false
                val message = workInfo.outputData.getString(PersistentMediaWorker.KEY_RESULT_MESSAGE)
                    ?: "فشلت المهمة الخلفية."
                if (type == PersistentMediaWorker.TYPE_LOCAL) _conversionState.value = ProcessingState.Error(message)
                else _downloadState.value = DownloadState.Error(message)
                _actionMessage.value = message
            }
            WorkInfo.State.CANCELLED -> {
                val taskPath = workInfo.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX)
                val paused = taskPath != null && withContext(Dispatchers.IO) {
                    BackgroundMediaTaskStore.isPaused(File(taskPath))
                }
                _backgroundWorkPaused.value = paused
                if (paused) {
                    if (type == PersistentMediaWorker.TYPE_LOCAL) {
                        _conversionState.value = ProcessingState.Processing(
                            progress,
                            "المهمة متوقفة مؤقتًا. اضغط استئناف لمتابعة التحويل."
                        )
                    } else {
                        _downloadState.value = DownloadState.Downloading(
                            currentItemTitle = itemTitle,
                            currentIndex = workInfo.progress.getInt(PersistentMediaWorker.KEY_CURRENT_INDEX, 1).coerceAtLeast(1),
                            totalCount = workInfo.progress.getInt(PersistentMediaWorker.KEY_TOTAL_COUNT, inputCount).coerceAtLeast(1),
                            progress = progress,
                            bytesDownloaded = workInfo.progress.getLong(PersistentMediaWorker.KEY_BYTES_DOWNLOADED, 0L),
                            totalBytes = workInfo.progress.getLong(PersistentMediaWorker.KEY_TOTAL_BYTES, 0L)
                        )
                    }
                    _actionMessage.value = "أُوقفت المهمة مؤقتًا. العناصر المكتملة محفوظة؛ قد يُعاد العنصر الجاري من بدايته عند الاستئناف."
                } else {
                    _backgroundWorkPaused.value = false
                    if (taskPath != null) {
                        withContext(Dispatchers.IO) {
                            val taskFile = File(taskPath)
                            val task = runCatching { BackgroundMediaTaskStore.read(taskFile) }.getOrNull()
                            val hasSavedLocalOutput = task is StoredMediaTask.LocalConversion &&
                                repository.itemsFlow.value.any { it.id == task.id && File(it.filePath).isFile }
                            BackgroundMediaTaskStore.delete(
                                taskFile,
                                task,
                                deleteOutput = task is StoredMediaTask.LocalConversion && !hasSavedLocalOutput
                            )
                        }
                    }
                    if (type == PersistentMediaWorker.TYPE_LOCAL) _conversionState.value = ProcessingState.Idle
                    else _downloadState.value = DownloadState.Idle
                    _actionMessage.value = "تم إلغاء المهمة الخلفية."
                }
            }
            else -> Unit
        }
    }

    fun pauseBackgroundWork() {
        if (!reserveBackgroundEnqueue()) {
            _actionMessage.value = "تجري عملية أخرى على المهمة؛ انتظر لحظة ثم أعد المحاولة."
            return
        }
        viewModelScope.launch {
            val markedFiles = mutableListOf<File>()
            try {
                val activeInfos = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosByTag(PersistentMediaWorker.TAG).get()
                        .filterNot { it.state.isFinished }
                }
                if (activeInfos.isEmpty()) {
                    _actionMessage.value = "لا توجد مهمة قيد التشغيل لإيقافها مؤقتًا."
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    activeInfos.forEach { info ->
                        info.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX)?.let { path ->
                            val taskFile = File(path)
                            BackgroundMediaTaskStore.markPaused(taskFile)
                            markedFiles += taskFile
                        }
                    }
                }
                check(markedFiles.isNotEmpty()) { "تعذر العثور على سجل المهمة لإيقافها مؤقتًا." }
                val cancellation = workManager.cancelAllWorkByTag(PersistentMediaWorker.TAG)
                val stoppedInfos = withContext(Dispatchers.IO) {
                    cancellation.result.get()
                    workManager.getWorkInfosByTagFlow(PersistentMediaWorker.TAG).first { infos ->
                        infos.none { !it.state.isFinished }
                    }
                    workManager.getWorkInfosByTag(PersistentMediaWorker.TAG).get()
                }
                val actuallyPaused = stoppedInfos.any { info ->
                    info.state == WorkInfo.State.CANCELLED &&
                        info.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX)
                            ?.let { BackgroundMediaTaskStore.isPaused(File(it)) } == true
                }
                _backgroundWorkPaused.value = actuallyPaused
                _actionMessage.value = if (actuallyPaused) {
                    "أُوقفت المهمة مؤقتًا. العناصر المكتملة محفوظة؛ يستأنف تنزيل الملف من إزاحته المحفوظة إذا دعم الخادم HTTP Range ومُعرّف المورد، وإلا يُبلّغ عن التعذر بدل خلط البيانات."
                } else {
                    "اكتملت المهمة قبل تنفيذ الإيقاف المؤقت."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { markedFiles.forEach { BackgroundMediaTaskStore.clearPaused(it) } }
                _backgroundWorkPaused.value = false
                _actionMessage.value = "تعذر إيقاف المهمة مؤقتًا: ${error.localizedMessage ?: "خطأ غير معروف"}"
            } finally {
                releaseBackgroundEnqueue()
            }
        }
    }

    fun resumeBackgroundWork() {
        if (!reserveBackgroundEnqueue()) {
            _actionMessage.value = "تجري عملية أخرى على المهمة؛ انتظر لحظة ثم أعد المحاولة."
            return
        }
        viewModelScope.launch {
            try {
                val pausedInfo = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosByTag(PersistentMediaWorker.TAG).get()
                        .filter { info ->
                            info.state == WorkInfo.State.CANCELLED &&
                                info.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX)
                                    ?.let { BackgroundMediaTaskStore.isPaused(File(it)) } == true
                        }
                        .maxByOrNull {
                            it.taggedValue(PersistentMediaWorker.TAG_CREATED_AT_PREFIX)?.toLongOrNull() ?: 0L
                        }
                } ?: run {
                    _backgroundWorkPaused.value = false
                    _actionMessage.value = "لا توجد مهمة متوقفة مؤقتًا لاستئنافها."
                    return@launch
                }
                val hasRunningWork = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosByTag(PersistentMediaWorker.TAG).get()
                        .any { !it.state.isFinished }
                }
                if (hasRunningWork) {
                    _actionMessage.value = "هناك مهمة أخرى تعمل بالفعل؛ لا يمكن استئناف المهمة الآن."
                    return@launch
                }
                val taskPath = pausedInfo.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX)
                    ?: throw IllegalStateException("تعذر العثور على ملف المهمة المحفوظة.")
                val taskFile = File(taskPath)
                val task = withContext(Dispatchers.IO) { BackgroundMediaTaskStore.read(taskFile) }
                val workRequest = buildPersistentWorkRequest(taskFile, task, isResume = true)
                val operation = workManager.beginUniqueWork(
                    PersistentMediaWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    workRequest
                ).enqueue()
                withContext(Dispatchers.IO) { operation.result.get() }
                _backgroundWorkPaused.value = false
                _actionMessage.value = if (task is StoredMediaTask.DownloadBatch) {
                    "استؤنف التحميل؛ العناصر المكتملة محفوظة، ويُطلب استكمال الملف من إزاحته المحفوظة مع التحقق من مُعرّف المورد ودعم HTTP Range."
                } else {
                    "استؤنف التحويل. سيُعاد التحويل الجاري من بدايته، مع بقاء الملف المصدر محفوظًا."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _backgroundWorkPaused.value = true
                _actionMessage.value = "تعذر استئناف المهمة: ${error.localizedMessage ?: "خطأ غير معروف"}"
            } finally {
                releaseBackgroundEnqueue()
            }
        }
    }

    fun cancelBackgroundWork() {
        _downloadState.value = DownloadState.Idle
        _conversionState.value = ProcessingState.Idle
        _backgroundWorkPaused.value = false
        _actionMessage.value = "جارٍ إلغاء المهمة وتنظيف الملفات المؤقتة…"
        viewModelScope.launch(Dispatchers.IO) {
            val infos = runCatching { workManager.getWorkInfosByTag(PersistentMediaWorker.TAG).get() }
                .getOrDefault(emptyList())
            infos.forEach { info ->
                info.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX)?.let { path ->
                    BackgroundMediaTaskStore.clearPaused(File(path))
                }
            }
            val cancellation = workManager.cancelAllWorkByTag(PersistentMediaWorker.TAG)
            runCatching { cancellation.result.get() }
            val stoppedInfos = runCatching {
                workManager.getWorkInfosByTagFlow(PersistentMediaWorker.TAG).first { workInfos ->
                    workInfos.none { !it.state.isFinished }
                }
            }.getOrDefault(emptyList())
            stoppedInfos.filter { it.state == WorkInfo.State.CANCELLED }.forEach { info ->
                val path = info.taggedValue(PersistentMediaWorker.TAG_TASK_FILE_PREFIX) ?: return@forEach
                val taskFile = File(path)
                val task = runCatching { BackgroundMediaTaskStore.read(taskFile) }.getOrNull()
                if (task is StoredMediaTask.DownloadBatch) {
                    task.requests.forEach { downloader.cleanupResumableDownload(it.mediaItemId) }
                }
                val hasSavedLocalOutput = task is StoredMediaTask.LocalConversion &&
                    repository.itemsFlow.value.any { it.id == task.id && File(it.filePath).isFile }
                BackgroundMediaTaskStore.delete(
                    taskFile = taskFile,
                    task = task,
                    deleteOutput = task is StoredMediaTask.LocalConversion && !hasSavedLocalOutput
                )
            }
        }
    }

    fun updateDownloadUrl(url: String) {
        _downloadUrl.value = url
    }

    fun setTargetDownloadFormat(format: AudioFormat) {
        _targetDownloadFormat.value = format
    }

    fun setTargetDownloadSpeed(speed: Float) {
        _targetDownloadSpeed.value = speed
    }

    fun setTargetDownloadQuality(quality: VideoQuality) {
        _targetDownloadQuality.value = quality
    }

    fun setTargetDownloadBitrate(bitrate: AudioBitrate) {
        _targetDownloadBitrate.value = bitrate
    }

    fun setTargetLocalSpeed(speed: Float) {
        _targetLocalSpeed.value = speed
    }

    fun setTargetLocalFormat(format: AudioFormat) {
        _targetLocalFormat.value = format
    }

    fun setTargetLocalQuality(quality: VideoQuality) {
        _targetLocalQuality.value = quality
    }

    fun setTargetLocalBitrate(bitrate: AudioBitrate) {
        _targetLocalBitrate.value = bitrate
    }

    fun showActionMessage(msg: String) {
        _actionMessage.value = msg
    }

    fun dismissActionMessage() {
        _actionMessage.value = null
    }

    fun resolveDuplicateConflict(action: DuplicateAction) {
        val current = _duplicateConflict.value
        _duplicateConflict.value = null
        current?.pendingAction?.invoke(action)
    }

    /**
     * Inspects link: detects YouTube video, playlist, or direct files from any website
     */
    fun analyzeUrl() {
        val url = _downloadUrl.value.trim()
        if (url.isEmpty()) return

        viewModelScope.launch {
            _downloadState.value = DownloadState.FetchingMetadata(url)
            try {
                val info = downloader.inspectUrl(url)
                if (info.items.isEmpty()) {
                    _downloadState.value = DownloadState.Error(
                        "تعذر استخراج عناصر من الرابط. تحقق من الرابط أو جرّب مرة أخرى."
                    )
                } else if (info.isPlaylist) {
                    _downloadState.value = DownloadState.PlaylistReady(
                        playlistTitle = info.playlistTitle,
                        items = info.items
                    )
                } else {
                    _downloadState.value = DownloadState.PlaylistReady(
                        playlistTitle = if (info.items[0].isVideo) "فيديو جاهز للتحميل" else "ملف جاهز للتحميل",
                        items = info.items
                    )
                }
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Error("خطأ في الاتصال بالرابط: ${e.localizedMessage}")
            }
        }
    }

    fun togglePlaylistItemSelection(itemId: String) {
        val state = _downloadState.value
        if (state is DownloadState.PlaylistReady) {
            val updated = state.items.map {
                if (it.id == itemId) it.copy(isSelected = !it.isSelected) else it
            }
            _downloadState.value = state.copy(items = updated)
        }
    }

    fun toggleSelectAllPlaylist(select: Boolean) {
        val state = _downloadState.value
        if (state is DownloadState.PlaylistReady) {
            val updated = state.items.map { it.copy(isSelected = select) }
            _downloadState.value = state.copy(items = updated)
        }
    }

    /**
     * Plans and checks every selected output independently. Conflicts are presented one at a
     * time; the selected action is stored only for that item and exact destination.
     */
    fun startDownload() {
        val state = _downloadState.value
        val itemsToDownload = when (state) {
            is DownloadState.PlaylistReady -> state.items.filter { it.isSelected }
            else -> emptyList()
        }

        if (itemsToDownload.isEmpty()) {
            _actionMessage.value = "يرجى تحديد عنصر واحد على الأقل للتحميل"
            return
        }

        val format = _targetDownloadFormat.value
        val speed = _targetDownloadSpeed.value
        val quality = _targetDownloadQuality.value
        val bitrate = _targetDownloadBitrate.value
        val isOriginalFormat = format == AudioFormat.ORIGINAL
        val isVideoFormat = !isOriginalFormat &&
            (format == AudioFormat.MP4 || format == AudioFormat.MKV || format == AudioFormat.WEBM)
        val speedTag = if (!isOriginalFormat && kotlin.math.abs(speed - 1.0f) > 0.01f) "_${speed}x" else ""
        val qualityTag = if (isVideoFormat) "_${quality.height}p" else ""
        val plannedExtensions = if (isOriginalFormat) {
            itemsToDownload.map { item ->
                DirectDownloadMetadataResolver.extensionFrom(
                    contentTypeHeader = item.sourceMimeType,
                    fileName = item.sourceFileName,
                    url = item.directStreamUrl.ifEmpty { item.url }
                )
            }
        } else null
        val filePlans = DownloadFilePlanner.plan(
            titles = itemsToDownload.map { it.title },
            extension = format.extension.ifBlank { "bin" },
            suffix = "$qualityTag$speedTag",
            existingFileNames = downloader.existingOutputFileNames(),
            extensions = plannedExtensions
        )
        if (filePlans.size != itemsToDownload.size) {
            _actionMessage.value = "تعذر تجهيز أسماء ملفات التحميل."
            return
        }

        val requests = itemsToDownload.zip(filePlans) { item, plan ->
            PlannedDownloadRequest(item = item, filePlan = plan)
        }
        val conflictIndices = requests.indices.filter { requests[it].filePlan.conflictsWithExistingFile }
        if (conflictIndices.isEmpty()) {
            executeDownload(requests, format, speed, quality, bitrate)
        } else {
            promptForNextConflict(
                requests = requests,
                conflictIndices = conflictIndices,
                conflictCursor = 0,
                decisions = mutableMapOf(),
                format = format,
                speed = speed,
                quality = quality,
                bitrate = bitrate
            )
        }
    }

    private fun promptForNextConflict(
        requests: List<PlannedDownloadRequest>,
        conflictIndices: List<Int>,
        conflictCursor: Int,
        decisions: MutableMap<Int, DuplicateAction>,
        format: AudioFormat,
        speed: Float,
        quality: VideoQuality,
        bitrate: AudioBitrate
    ) {
        if (conflictCursor >= conflictIndices.size) {
            val finalizedRequests = requests.mapIndexed { index, request ->
                request.copy(
                    duplicateAction = request.filePlan.actionFor(
                        decisions[index] ?: DuplicateAction.DOWNLOAD_ANYWAY
                    )
                )
            }
            _duplicateConflict.value = null
            executeDownload(finalizedRequests, format, speed, quality, bitrate)
            return
        }

        val requestIndex = conflictIndices[conflictCursor]
        val request = requests[requestIndex]
        _duplicateConflict.value = DuplicateConflict(
            fileName = request.filePlan.targetFileName,
            itemTitle = request.item.title,
            conflictIndex = conflictCursor + 1,
            conflictCount = conflictIndices.size,
            pendingAction = { choice ->
                _duplicateConflict.value = null
                if (choice == DuplicateAction.CANCEL) {
                    _actionMessage.value = "تم إلغاء التحميل"
                } else {
                    decisions[requestIndex] = choice
                    promptForNextConflict(
                        requests = requests,
                        conflictIndices = conflictIndices,
                        conflictCursor = conflictCursor + 1,
                        decisions = decisions,
                        format = format,
                        speed = speed,
                        quality = quality,
                        bitrate = bitrate
                    )
                }
            }
        )
    }

    private fun executeDownload(
        requests: List<PlannedDownloadRequest>,
        format: AudioFormat,
        speed: Float,
        quality: VideoQuality,
        bitrate: AudioBitrate
    ) {
        if (requests.isEmpty()) return
        if (!reserveBackgroundEnqueue()) {
            _actionMessage.value = "جارٍ تجهيز مهمة خلفية أخرى؛ انتظر لحظة ثم أعد المحاولة."
            return
        }
        viewModelScope.launch {
            try {
                if (!hasNoActiveBackgroundWork()) {
                    _actionMessage.value = "هناك مهمة خلفية قيد التنفيذ بالفعل. ألغها أو انتظر اكتمالها قبل بدء مهمة أخرى."
                    return@launch
                }
                val taskId = UUID.randomUUID().toString()
                val createdAt = System.currentTimeMillis()
                val taskTitle = (if (requests.size == 1) requests.first().item.title else "تحميل ${requests.size} ملفات")
                    .take(180)
                val storedRequests = requests.mapIndexed { index, request ->
                    DownloadRequest(
                        item = request.item,
                        outputFileName = request.filePlan.outputFileNameFor(request.duplicateAction),
                        mediaItemId = UUID.nameUUIDFromBytes("$taskId:${request.item.id}:$index".toByteArray(Charsets.UTF_8)).toString()
                    )
                }
                val task = StoredMediaTask.DownloadBatch(
                    id = taskId,
                    createdAt = createdAt,
                    title = taskTitle,
                    format = format,
                    speed = speed,
                    quality = quality,
                    bitrate = bitrate,
                    requests = storedRequests
                )
                var taskFile: File? = null
                var enqueueSubmitted = false
                try {
                    val descriptor = withContext(Dispatchers.IO) {
                        BackgroundMediaTaskStore.write(getApplication<Application>(), task)
                    }
                    taskFile = descriptor
                    val workRequest = buildPersistentWorkRequest(descriptor, task)
                    val enqueueOperation = workManager.beginUniqueWork(
                        PersistentMediaWorker.UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        workRequest
                    ).enqueue()
                    enqueueSubmitted = true
                    withContext(Dispatchers.IO) { enqueueOperation.result.get() }
                    _downloadState.value = DownloadState.Downloading(
                        currentItemTitle = taskTitle,
                        currentIndex = 1,
                        totalCount = requests.size,
                        progress = 0f,
                        bytesDownloaded = 0L,
                        totalBytes = 0L
                    )
                    _actionMessage.value = "بدأت المهمة؛ سيستمر التحميل والتحويل في الخلفية مع إمكانية الإلغاء."
                } catch (cancelled: CancellationException) {
                    if (!enqueueSubmitted) taskFile?.let { BackgroundMediaTaskStore.delete(it, task) }
                    throw cancelled
                } catch (error: Exception) {
                    taskFile?.let { BackgroundMediaTaskStore.delete(it, task) }
                    val message = "تعذر بدء المهمة الخلفية: ${error.localizedMessage ?: "خطأ غير معروف"}"
                    _downloadState.value = DownloadState.Error(message)
                    _actionMessage.value = message
                }
            } finally {
                releaseBackgroundEnqueue()
            }
        }
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Local File selection: inspect metadata
     */
    fun onLocalFileSelected(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val persistedGrant = try {
            resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: Exception) {
            resolver.persistedUriPermissions.any { permission ->
                permission.uri == uri &&
                    permission.isReadPermission
            }
        }
        _selectedLocalUri.value = uri
        _selectedUriPersistable.value = persistedGrant
        viewModelScope.launch {
            _conversionState.value = ProcessingState.Processing(0.1f, "فحص بيانات وجودة الملف...")
            val meta = repository.inspectLocalUri(uri)
            _selectedLocalMetadata.value = meta
            _conversionState.value = ProcessingState.Idle
        }
    }

    fun previewLocalFile() {
        val uri = _selectedLocalUri.value ?: return
        val meta = _selectedLocalMetadata.value
        player.setSpeed(_targetLocalSpeed.value)
        player.playUri(uri, meta?.title ?: "معاينة الملف")
    }

    /**
     * Converts / Downscales local video or audio file
     */
    fun startLocalConversion() {
        val uri = _selectedLocalUri.value ?: run {
            _actionMessage.value = "يرجى اختيار ملف ميديا أولاً"
            return
        }
        val meta = _selectedLocalMetadata.value
        val speed = _targetLocalSpeed.value
        val quality = _targetLocalQuality.value
        val format = _targetLocalFormat.value
        val bitrate = _targetLocalBitrate.value
        val isVideoSource = meta?.isVideo == true
        val isVideoOutput = format in setOf(AudioFormat.MP4, AudioFormat.MKV, AudioFormat.WEBM)
        if (isVideoOutput && !isVideoSource) {
            _conversionState.value = ProcessingState.Error("صيغة الفيديو تتطلب مصدرًا يحتوي على مسار فيديو.")
            return
        }
        if (!reserveBackgroundEnqueue()) {
            _actionMessage.value = "جارٍ تجهيز مهمة خلفية أخرى؛ انتظر لحظة ثم أعد المحاولة."
            return
        }

        viewModelScope.launch {
            try {
                if (!hasNoActiveBackgroundWork()) {
                    _actionMessage.value = "هناك مهمة خلفية قيد التنفيذ بالفعل. ألغها أو انتظر اكتمالها قبل بدء مهمة أخرى."
                    return@launch
                }
                val taskId = UUID.randomUUID().toString()
                val cleanTitle = (meta?.title ?: "ملف_${System.currentTimeMillis()}")
                    .replace(Regex("[^a-zA-Z0-9أ-ي_\\-\\s]"), "")
                    .trim()
                    .ifEmpty { "ملف" }
                val videoLabel = if (isVideoOutput) "_video_${speed}x" else ""
                val speedLabel = if (!isVideoOutput && speed != 1.0f) "_${speed}x" else ""
                val qualityLabel = if (!isVideoOutput) "_${bitrate.label}" else ""
                val outputDirectory = File(getApplication<Application>().filesDir, "converted_audio")
                val outputFile = File(
                    outputDirectory,
                    "${cleanTitle}${videoLabel}${speedLabel}${qualityLabel}_${taskId.take(8)}.${format.extension}"
                )
                var stagedSource: File? = null
                var taskFile: File? = null
                var enqueued = false
                var enqueueSubmitted = false
                try {
                    var stagedSourceFile: File? = null
                    if (!_selectedUriPersistable.value) {
                        _conversionState.value = ProcessingState.Processing(0.02f, "نسخ المصدر لتأمين استمرار التحويل…")
                        stagedSourceFile = BackgroundMediaTaskStore.stageLocalSource(
                            context = getApplication<Application>(),
                            uri = uri,
                            taskId = taskId,
                            onProgress = { copied, total ->
                                val progress = if (total > 0L) (copied.toFloat() / total).coerceIn(0f, 1f) else 0f
                                _conversionState.value = ProcessingState.Processing(
                                    0.02f + progress * 0.08f,
                                    "حفظ المصدر لاستمرار التحويل في الخلفية…"
                                )
                            }
                        )
                        stagedSource = stagedSourceFile
                    } else {
                        _conversionState.value = ProcessingState.Processing(0.08f, "تحضير وصول آمن للمصدر…")
                    }
                    val createdAt = System.currentTimeMillis()
                    val taskTitle = cleanTitle.take(180)
                    val task = StoredMediaTask.LocalConversion(
                        id = taskId,
                        createdAt = createdAt,
                        title = taskTitle,
                        sourcePath = stagedSourceFile?.absolutePath.orEmpty(),
                        sourceUri = uri.toString(),
                        outputPath = outputFile.absolutePath,
                        originalDurationMs = meta?.durationMs ?: 0L,
                        isVideoSource = isVideoSource,
                        speed = speed,
                        format = format,
                        quality = quality,
                        bitrate = bitrate
                    )
                    val descriptor = withContext(Dispatchers.IO) {
                        BackgroundMediaTaskStore.write(getApplication<Application>(), task)
                    }
                    taskFile = descriptor
                    val workRequest = buildPersistentWorkRequest(descriptor, task)
                    val enqueueOperation = workManager.beginUniqueWork(
                        PersistentMediaWorker.UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        workRequest
                    ).enqueue()
                    enqueueSubmitted = true
                    withContext(Dispatchers.IO) { enqueueOperation.result.get() }
                    enqueued = true
                    _conversionState.value = ProcessingState.Processing(0.1f, "أُضيف التحويل إلى المهام الخلفية؛ يمكنك إغلاق التطبيق بأمان.")
                    _actionMessage.value = "بدأ التحويل في الخلفية. سيظهر إشعار مستمر ويمكنك إلغاء المهمة منه أو من التطبيق."
                } catch (cancelled: CancellationException) {
                    if (!enqueueSubmitted) {
                        taskFile?.let { BackgroundMediaTaskStore.delete(it, null) }
                        stagedSource?.delete()
                        outputFile.delete()
                    }
                    throw cancelled
                } catch (error: Exception) {
                    if (!enqueued) {
                        taskFile?.let { BackgroundMediaTaskStore.delete(it, null) }
                        stagedSource?.delete()
                        outputFile.delete()
                    }
                    val message = "تعذر بدء التحويل في الخلفية: ${error.localizedMessage ?: "خطأ غير معروف"}"
                    _conversionState.value = ProcessingState.Error(message)
                    _actionMessage.value = message
                }
            } finally {
                releaseBackgroundEnqueue()
            }
        }
    }

    /**
     * Web Search engine implementation (YouTube / Google / Direct Files)
     */
    fun performWebSearch(query: String, engine: SearchEngine) {
        viewModelScope.launch {
            _isSearching.value = true
            val results = mutableListOf<SearchResultItem>()

            withContext(Dispatchers.IO) {
                try {
                    when (engine) {
                        SearchEngine.YOUTUBE -> {
                            val encoded = URLEncoder.encode(query, "UTF-8")
                            // 1. Try public Invidious / Piped search
                            val searchUrl = "https://inv.tux.pizza/api/v1/search?q=$encoded&type=video"
                            val req = Request.Builder()
                                .url(searchUrl)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .build()
                            val resp = try { httpClient.newCall(req).execute() } catch (_: Exception) { null }

                            if (resp != null && resp.isSuccessful) {
                                val jsonArray = JSONArray(resp.body?.string() ?: "[]")
                                for (i in 0 until minOf(jsonArray.length(), 15)) {
                                    val obj = jsonArray.getJSONObject(i)
                                    val videoId = obj.optString("videoId", "")
                                    val title = obj.optString("title", "فيديو بدون عنوان")
                                    val author = obj.optString("author", "قناة يوتيوب")
                                    val durationSec = obj.optLong("lengthSeconds", 0L)
                                    val durationStr = if (durationSec > 0) "${durationSec / 60}:${String.format("%02d", durationSec % 60)}" else "فيديو"
                                    if (videoId.isNotEmpty()) {
                                        results.add(
                                            SearchResultItem(
                                                title = title,
                                                sourceOrAuthor = author,
                                                durationOrType = durationStr,
                                                url = "https://www.youtube.com/watch?v=$videoId",
                                                thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                                                snippet = obj.optString("description", "")
                                            )
                                        )
                                    }
                                }
                            }

                            // If external API was blocked, scrape YouTube search web results
                            if (results.isEmpty()) {
                                val ytSearchUrl = "https://www.youtube.com/results?search_query=$encoded"
                                val ytReq = Request.Builder()
                                    .url(ytSearchUrl)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                    .header("Accept-Language", "ar,en;q=0.9")
                                    .build()
                                val ytResp = try { httpClient.newCall(ytReq).execute() } catch (_: Exception) { null }
                                if (ytResp != null && ytResp.isSuccessful) {
                                    val html = ytResp.body?.string() ?: ""
                                    val pattern = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\",\"thumbnail\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"")
                                    val matcher = pattern.matcher(html)
                                    var count = 0
                                    while (matcher.find() && count < 10) {
                                        val vId = matcher.group(1) ?: continue
                                        val vTitle = matcher.group(2) ?: "مقطع يوتيوب"
                                        results.add(
                                            SearchResultItem(
                                                title = vTitle,
                                                sourceOrAuthor = "يوتيوب",
                                                durationOrType = "فيديو MP4",
                                                url = "https://www.youtube.com/watch?v=$vId",
                                                thumbnailUrl = "https://img.youtube.com/vi/$vId/hqdefault.jpg"
                                            )
                                        )
                                        count++
                                    }
                                }
                            }
                        }

                        SearchEngine.GOOGLE -> {
                            val encoded = URLEncoder.encode(query, "UTF-8")
                            val googleUrl = "https://html.duckduckgo.com/html/?q=$encoded"
                            val req = Request.Builder()
                                .url(googleUrl)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .build()
                            val resp = try { httpClient.newCall(req).execute() } catch (_: Exception) { null }
                            if (resp != null && resp.isSuccessful) {
                                val html = resp.body?.string() ?: ""
                                val titlePattern = Pattern.compile("<a class=\"result__url\" href=\"([^\"]+)\">([^<]+)</a>")
                                val matcher = titlePattern.matcher(html)
                                var count = 0
                                while (matcher.find() && count < 10) {
                                    val link = matcher.group(1)?.trim() ?: continue
                                    val linkTitle = matcher.group(2)?.trim() ?: link
                                    results.add(
                                        SearchResultItem(
                                            title = linkTitle,
                                            sourceOrAuthor = "بحث جوجل والويب",
                                            durationOrType = "رابط ويب",
                                            url = if (link.startsWith("http")) link else "https://$link",
                                            snippet = "نتيجة بحث ويب جاهزة للتحميل"
                                        )
                                    )
                                    count++
                                }
                            }
                        }

                        SearchEngine.DIRECT_FILES -> {
                            // Search Archive.org media API for direct audio/video/document files
                            val encoded = URLEncoder.encode(query, "UTF-8")
                            val archiveUrl = "https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title,creator,mediatype&rows=12&output=json"
                            val req = Request.Builder().url(archiveUrl).build()
                            val resp = try { httpClient.newCall(req).execute() } catch (_: Exception) { null }
                            if (resp != null && resp.isSuccessful) {
                                val json = JSONObject(resp.body?.string() ?: "{}")
                                val docs = json.optJSONObject("response")?.optJSONArray("docs")
                                if (docs != null) {
                                    for (i in 0 until docs.length()) {
                                        val doc = docs.getJSONObject(i)
                                        val id = doc.optString("identifier", "")
                                        val title = doc.optString("title", id)
                                        val creator = doc.optString("creator", "أرشيف الملفات")
                                        val mediaType = doc.optString("mediatype", "ملف")
                                        if (id.isNotEmpty()) {
                                            results.add(
                                                SearchResultItem(
                                                    title = title,
                                                    sourceOrAuthor = creator,
                                                    durationOrType = mediaType,
                                                    url = "https://archive.org/download/$id",
                                                    thumbnailUrl = "https://archive.org/services/img/$id",
                                                    snippet = "ملف مباشر متاح للتنزيل"
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    fun playItem(item: MediaItem) {
        player.setSpeed(1.0f)
        player.playItem(item)
    }

    fun deleteItem(item: MediaItem) {
        viewModelScope.launch {
            val deleted = repository.deleteItem(item)
            _actionMessage.value = if (deleted) "تم حذف الملف" else {
                repository.storageError.value ?: "تعذر حفظ تغييرات سجل المكتبة."
            }
        }
    }

    fun openWithExternalApp(item: MediaItem) {
        val intent = repository.openFileIntent(item)
        if (intent != null) {
            try {
                getApplication<Application>().startActivity(intent)
            } catch (e: Exception) {
                _actionMessage.value = "لا يوجد تطبيق مناسب لفتح هذا النوع من الملفات"
            }
        }
    }

    fun exportToDownloads(item: MediaItem) {
        viewModelScope.launch {
            val success = repository.exportToPublicDownloads(item)
            _actionMessage.value = if (success) {
                "تم تصدير الملف إلى مجلد Downloads/File Station بالجهاز"
            } else {
                "فشل تصدير الملف إلى مجلد التنزيلات"
            }
        }
    }

    fun exportToDocument(item: MediaItem, destinationUri: Uri) {
        viewModelScope.launch {
            val success = repository.exportToDocument(item, destinationUri)
            _actionMessage.value = if (success) {
                "تم حفظ نسخة من الملف في الموقع الذي اخترته"
            } else {
                "فشل حفظ الملف في الموقع المختار"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
