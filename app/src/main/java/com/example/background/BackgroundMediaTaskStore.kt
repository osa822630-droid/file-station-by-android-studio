package com.example.background

import android.content.Context
import android.net.Uri
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
import java.io.FileOutputStream
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
        partial.delete()
        try {
            val totalBytes = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    it.length.takeIf { length -> length >= 0L } ?: -1L
                } ?: -1L
            }.getOrDefault(-1L)
            require(totalBytes <= MAX_LOCAL_SOURCE_BYTES) {
                "حجم ملف المصدر يتجاوز الحد المسموح به وهو 1 GiB."
            }
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("تعذر فتح الملف المحدد.")
            input.buffered().use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= MAX_LOCAL_SOURCE_BYTES) {
                            "حجم ملف المصدر يتجاوز الحد المسموح به وهو 1 GiB."
                        }
                        output.write(buffer, 0, read)
                        onProgress(copied, totalBytes)
                    }
                    output.fd.sync()
                }
            }
            check(partial.isFile && partial.length() > 0L) { "ملف المصدر فارغ أو غير قابل للقراءة." }
            check(partial.renameTo(destination)) { "تعذر حفظ نسخة المصدر للتحويل في الخلفية." }
            destination
        } catch (error: Exception) {
            partial.delete()
            destination.delete()
            throw error
        }
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
