package com.example.background

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import com.example.model.AudioBitrate
import com.example.model.AudioFormat
import com.example.model.FileType
import com.example.model.PlaylistItem
import com.example.model.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal sealed class StoredMediaTask {
    abstract val id: String
    abstract val createdAt: Long
    abstract val title: String
    abstract val speed: Float

    data class DownloadBatch(
        override val id: String,
        override val createdAt: Long,
        override val title: String,
        val format: AudioFormat,
        override val speed: Float,
        val quality: VideoQuality,
        val bitrate: AudioBitrate,
        val requests: List<DownloadRequest>
    ) : StoredMediaTask()

    data class LocalConversion(
        override val id: String,
        override val createdAt: Long,
        override val title: String,
        val sourcePath: String,
        val sourceUri: String,
        val outputPath: String,
        val originalDurationMs: Long,
        val isVideoSource: Boolean,
        override val speed: Float,
        val format: AudioFormat,
        val quality: VideoQuality,
        val bitrate: AudioBitrate
    ) : StoredMediaTask()
}

internal data class DownloadRequest(
    val item: PlaylistItem,
    val outputFileName: String,
    val mediaItemId: String
)

/** Stores worker payloads and local input copies outside cache so Android may kill the app safely. */
internal object BackgroundMediaTaskStore {
    const val MAX_LOCAL_SOURCE_BYTES = 1L * 1024 * 1024 * 1024

    fun taskFile(context: Context, taskId: String): File =
        File(File(context.filesDir, "media_work/tasks"), "$taskId.json")

    fun isPaused(taskFile: File): Boolean = File(taskFile.path + ".paused").isFile

    fun markPaused(taskFile: File) {
        val marker = File(taskFile.path + ".paused")
        FileOutputStream(marker).use { output ->
            output.write(System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    fun clearPaused(taskFile: File) {
        File(taskFile.path + ".paused").delete()
    }

    private data class LocalSourceIdentity(
        val uri: String,
        val length: Long,
        val lastModified: Long,
        val sha256: String
    )

    private fun sourceResumeStateFile(sourceDirectory: File, taskId: String): File =
        File(sourceDirectory, "$taskId.source.resume.json")

    private fun sha256Hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    private fun queryLastModified(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private suspend fun fingerprintSource(context: Context, uri: Uri, expectedLength: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("تعذر التحقق من نسخة المصدر قبل استئنافها.")
        var counted = 0L
        input.buffered().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                counted += read
                require(counted <= MAX_LOCAL_SOURCE_BYTES) {
                    "حجم ملف المصدر يتجاوز الحد المسموح به وهو 1 GiB."
                }
                digest.update(buffer, 0, read)
            }
        }
        check(expectedLength <= 0L || counted == expectedLength) {
            "تغير حجم المصدر أثناء التحقق؛ لن يُستأنف التحويل على نسخة غير ثابتة."
        }
        return sha256Hex(digest.digest())
    }

    private fun readLocalSourceIdentity(file: File): LocalSourceIdentity? = runCatching {
        val json = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        LocalSourceIdentity(
            uri = json.getString("uri"),
            length = json.optLong("length", -1L),
            lastModified = json.optLong("lastModified", -1L),
            sha256 = json.optString("sha256", "")
        )
    }.getOrNull()

    private fun writeLocalSourceIdentity(file: File, identity: LocalSourceIdentity) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val json = JSONObject().apply {
                put("uri", identity.uri)
                put("length", identity.length)
                put("lastModified", identity.lastModified)
                put("sha256", identity.sha256)
            }
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    /**
     * Continue a SAF source copy from the exact durable prefix already in [partial].
     * The input is reopened at byte zero; the prefix is read and hashed to make sure
     * the persisted bytes still match this URI before appending the remaining bytes.
     */
    internal suspend fun resumeSourceCopyFromInputStream(
        source: InputStream,
        partial: File,
        totalBytes: Long,
        expectedSha256: String? = null,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        val offset = partial.length()
        require(offset <= MAX_LOCAL_SOURCE_BYTES && (totalBytes <= 0L || offset <= totalBytes)) {
            "حالة النسخ المرحلي غير صالحة؛ لن يُستأنف ملف مختلف."
        }
        val sourcePrefixDigest = MessageDigest.getInstance("SHA-256")
        val completeSourceDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var skipped = 0L
        while (skipped < offset) {
            currentCoroutineContext().ensureActive()
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), offset - skipped).toInt())
            if (read < 0) throw IllegalStateException("المصدر أقصر من الجزء المحفوظ؛ تعذر استئناف النسخ بأمان.")
            if (read == 0) continue
            sourcePrefixDigest.update(buffer, 0, read)
            completeSourceDigest.update(buffer, 0, read)
            skipped += read
        }
        if (offset > 0L) {
            val partialDigest = MessageDigest.getInstance("SHA-256")
            FileInputStream(partial).use { saved ->
                while (true) {
                    val read = saved.read(buffer)
                    if (read < 0) break
                    if (read > 0) partialDigest.update(buffer, 0, read)
                }
            }
            check(sourcePrefixDigest.digest().contentEquals(partialDigest.digest())) {
                "تغير مصدر التحويل منذ الإيقاف المؤقت؛ أُوقف الاستئناف لتجنب دمج نسختين مختلفتين."
            }
        }

        FileOutputStream(partial, offset > 0L).use { output ->
            var copied = offset
            var bytesSinceSync = 0L
            onProgress(copied, totalBytes)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copied += read
                    require(copied <= MAX_LOCAL_SOURCE_BYTES && (totalBytes <= 0L || copied <= totalBytes)) {
                        "حجم ملف المصدر يتجاوز الحد المسموح به وهو 1 GiB."
                    }
                    output.write(buffer, 0, read)
                    completeSourceDigest.update(buffer, 0, read)
                    bytesSinceSync += read
                    if (bytesSinceSync >= 1_048_576L) {
                        output.fd.sync()
                        bytesSinceSync = 0L
                    }
                    onProgress(copied, totalBytes)
                }
                output.fd.sync()
            } finally {
                runCatching { output.fd.sync() }
            }
            check(totalBytes <= 0L || copied == totalBytes) {
                "توقف تدفق المصدر قبل اكتمال النسخ؛ أُبقي الجزء المحفوظ للاستئناف."
            }
            check(expectedSha256.isNullOrBlank() ||
                sha256Hex(completeSourceDigest.digest()) == expectedSha256) {
                "تغير محتوى المصدر أثناء النسخ؛ لن يُنشر ملف يجمع بين نسختين مختلفتين."
            }
        }
    }

    suspend fun stageLocalSource(
        context: Context,
        uri: Uri,
        taskId: String,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val sourceDirectory = File(context.filesDir, "media_work/sources").apply {
            check(exists() || mkdirs()) { "تعذر تجهيز مساحة تخزين التحويل." }
        }
        val destination = File(sourceDirectory, "$taskId.source")
        val partial = File(sourceDirectory, "$taskId.source.partial")
        val identityFile = sourceResumeStateFile(sourceDirectory, taskId)
        check(!destination.exists()) { "نسخة المصدر المحفوظة موجودة بالفعل؛ يجب إعادة استخدامها بدل نسخها مجددًا." }
        val totalBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length.takeIf { length -> length >= 0L } ?: -1L
            } ?: -1L
        }.getOrDefault(-1L)
        require(totalBytes <= MAX_LOCAL_SOURCE_BYTES) {
            "حجم ملف المصدر يتجاوز الحد المسموح به وهو 1 GiB."
        }
        val currentFingerprint = fingerprintSource(context, uri, totalBytes)
        val identity = LocalSourceIdentity(
            uri = uri.toString(),
            length = totalBytes,
            lastModified = queryLastModified(context, uri),
            sha256 = currentFingerprint
        )
        if (partial.length() > 0L) {
            val savedIdentity = readLocalSourceIdentity(identityFile)
                ?: throw IllegalStateException("بيانات نقطة استئناف المصدر مفقودة؛ لن أبدأ نسخ هذا العنصر من الصفر.")
            check(savedIdentity.uri == identity.uri &&
                (savedIdentity.length <= 0L || identity.length <= 0L || savedIdentity.length == identity.length) &&
                savedIdentity.sha256.isNotBlank() && savedIdentity.sha256 == identity.sha256 &&
                (savedIdentity.lastModified < 0L || identity.lastModified < 0L || savedIdentity.lastModified == identity.lastModified)) {
                "تغير المصدر منذ إيقاف المهمة؛ لن أخلط نسختين مختلفتين. أعد إضافة الملف كمهمة جديدة."
            }
        } else {
            writeLocalSourceIdentity(identityFile, identity)
        }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("تعذر فتح الملف المحدد.")
        input.buffered().use { source ->
            resumeSourceCopyFromInputStream(
                source = source,
                partial = partial,
                totalBytes = totalBytes,
                expectedSha256 = identity.sha256,
                onProgress = onProgress
            )
        }
        check(partial.isFile && partial.length() > 0L) { "ملف المصدر فارغ أو غير قابل للقراءة." }
        check(totalBytes <= 0L || partial.length() == totalBytes) {
            "حجم النسخة المرحلية لا يطابق حجم المصدر؛ أُبقيت نقطة الاستئناف كما هي."
        }
        check(partial.renameTo(destination)) { "تعذر حفظ نسخة المصدر للتحويل في الخلفية." }
        AtomicFile(identityFile).delete()
        destination
    }

    fun write(context: Context, task: StoredMediaTask): File {
        val directory = File(context.filesDir, "media_work/tasks").apply {
            check(exists() || mkdirs()) { "تعذر إنشاء سجل المهام الخلفية." }
        }
        val destination = taskFile(context, task.id)
        val atomicFile = AtomicFile(destination)
        val output = atomicFile.startWrite()
        try {
            output.write(encode(task).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
        return destination
    }

    fun read(file: File): StoredMediaTask {
        val raw = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(raw)
        val id = json.getString("id")
        val createdAt = json.optLong("createdAt", file.lastModified())
        val title = json.optString("title", "مهمة وسائط")
        return when (json.getString("type")) {
            TYPE_DOWNLOADS -> StoredMediaTask.DownloadBatch(
                id = id,
                createdAt = createdAt,
                title = title,
                format = enumValue(json.getString("format")),
                speed = json.getDouble("speed").toFloat(),
                quality = enumValue(json.getString("quality")),
                bitrate = enumValue(json.getString("bitrate")),
                requests = json.getJSONArray("requests").toDownloadRequests()
            )
            TYPE_LOCAL_CONVERSION -> StoredMediaTask.LocalConversion(
                id = id,
                createdAt = createdAt,
                title = title,
                sourcePath = json.getString("sourcePath"),
                sourceUri = json.optString("sourceUri", ""),
                outputPath = json.getString("outputPath"),
                originalDurationMs = json.optLong("originalDurationMs", 0L),
                isVideoSource = json.optBoolean("isVideoSource", false),
                speed = json.getDouble("speed").toFloat(),
                format = enumValue(json.getString("format")),
                quality = enumValue(json.getString("quality")),
                bitrate = enumValue(json.getString("bitrate"))
            )
            else -> throw IllegalArgumentException("نوع مهمة خلفية غير معروف.")
        }
    }

    fun delete(taskFile: File, task: StoredMediaTask?, deleteOutput: Boolean = false) {
        if (task is StoredMediaTask.LocalConversion) {
            if (task.sourcePath.isNotBlank()) {
                File(task.sourcePath).delete()
            } else {
                val sourceDirectory = File(taskFile.parentFile?.parentFile, "sources")
                File(sourceDirectory, "${task.id}.source").delete()
            }
            val sourceDirectory = File(taskFile.parentFile?.parentFile, "sources")
            File(sourceDirectory, "${task.id}.source.partial").delete()
            AtomicFile(sourceResumeStateFile(sourceDirectory, task.id)).delete()
            if (deleteOutput) File(task.outputPath).delete()
        }
        taskFile.delete()
        File(taskFile.path + ".bak").delete()
        clearPaused(taskFile)
    }

    fun encode(task: StoredMediaTask): String = JSONObject().apply {
        put("id", task.id)
        put("createdAt", task.createdAt)
        put("title", task.title)
        when (task) {
            is StoredMediaTask.DownloadBatch -> {
                put("type", TYPE_DOWNLOADS)
                put("format", task.format.name)
                put("speed", task.speed.toDouble())
                put("quality", task.quality.name)
                put("bitrate", task.bitrate.name)
                put("requests", JSONArray().apply {
                    task.requests.forEach { request ->
                        put(JSONObject().apply {
                            put("outputFileName", request.outputFileName)
                            put("mediaItemId", request.mediaItemId)
                            put("item", request.item.toJson())
                        })
                    }
                })
            }
            is StoredMediaTask.LocalConversion -> {
                put("type", TYPE_LOCAL_CONVERSION)
                put("sourcePath", task.sourcePath)
                put("sourceUri", task.sourceUri)
                put("outputPath", task.outputPath)
                put("originalDurationMs", task.originalDurationMs)
                put("isVideoSource", task.isVideoSource)
                put("speed", task.speed.toDouble())
                put("format", task.format.name)
                put("quality", task.quality.name)
                put("bitrate", task.bitrate.name)
            }
        }
    }.toString()

    private fun PlaylistItem.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("author", author)
        put("durationText", durationText)
        put("durationSeconds", durationSeconds)
        put("thumbnailUrl", thumbnailUrl)
        put("url", url)
        put("isSelected", isSelected)
        put("isVideo", isVideo)
        put("fileType", fileType.name)
        put("fileSizeEstimate", fileSizeEstimate)
        put("selectedQuality", selectedQuality.name)
        put("directStreamUrl", directStreamUrl)
        put("sourceMimeType", sourceMimeType)
        put("sourceFileName", sourceFileName)
    }

    private fun JSONArray.toDownloadRequests(): List<DownloadRequest> = buildList {
        for (index in 0 until length()) {
            val request = getJSONObject(index)
            val item = request.getJSONObject("item")
            add(
                DownloadRequest(
                    outputFileName = request.getString("outputFileName"),
                    mediaItemId = request.getString("mediaItemId"),
                    item = PlaylistItem(
                        id = item.getString("id"),
                        title = item.optString("title", "ملف"),
                        author = item.optString("author", ""),
                        durationText = item.optString("durationText", ""),
                        durationSeconds = item.optLong("durationSeconds", 0L),
                        thumbnailUrl = item.optString("thumbnailUrl", ""),
                        url = item.optString("url", ""),
                        isSelected = item.optBoolean("isSelected", true),
                        isVideo = item.optBoolean("isVideo", false),
                        fileType = enumValue(item.optString("fileType", FileType.OTHER.name)),
                        fileSizeEstimate = item.optLong("fileSizeEstimate", 0L),
                        selectedQuality = enumValue(item.optString("selectedQuality", VideoQuality.Q720P.name)),
                        directStreamUrl = item.optString("directStreamUrl", ""),
                        sourceMimeType = item.optString("sourceMimeType", ""),
                        sourceFileName = item.optString("sourceFileName", "")
                    )
                )
            )
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("قيمة نوع المهمة غير صالحة: $value")

    private const val TYPE_DOWNLOADS = "downloads"
    private const val TYPE_LOCAL_CONVERSION = "local_conversion"
}
