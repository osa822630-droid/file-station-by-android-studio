package com.example.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.example.model.FileType
import com.example.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

class MediaItemRepository(
    private val context: Context,
    private val storageFile: File = File(context.filesDir, "media_library.json")
) {

    private val atomicStorageFile = AtomicFile(storageFile)
    private val mutationMutex = Mutex()
    private val _itemsFlow = MutableStateFlow<List<MediaItem>>(emptyList())
    val itemsFlow = _itemsFlow.asStateFlow()
    private val _storageError = MutableStateFlow<String?>(null)
    val storageError = _storageError.asStateFlow()

    init {
        loadPersistedItems()
    }

    private fun loadPersistedItems() {
        val backupFile = File(storageFile.path + ".bak")
        if (!storageFile.exists() && !backupFile.exists()) return

        try {
            val content = atomicStorageFile.openRead().bufferedReader().use { it.readText() }
            val array = JSONArray(content)
            val list = mutableListOf<MediaItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val path = obj.getString("filePath")
                val file = File(path)
                if (file.exists()) {
                    val typeName = obj.optString("fileType", "")
                    val fileType = try {
                        if (typeName.isNotEmpty()) FileType.valueOf(typeName)
                        else if (obj.optBoolean("isVideo", false)) FileType.VIDEO
                        else FileType.AUDIO
                    } catch (_: Exception) {
                        FileType.AUDIO
                    }
                    list.add(
                        MediaItem(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            filePath = path,
                            durationMs = obj.getLong("durationMs"),
                            originalDurationMs = obj.optLong("originalDurationMs", obj.getLong("durationMs")),
                            speed = obj.getDouble("speed").toFloat(),
                            format = obj.getString("format"),
                            fileSize = file.length(),
                            isVideo = obj.optBoolean("isVideo", false),
                            fileType = fileType,
                            resolutionText = obj.optString("resolutionText", ""),
                            bitrateKbps = obj.optInt("bitrateKbps", 128),
                            dateAdded = obj.optLong("dateAdded", System.currentTimeMillis()),
                            sourceUrl = obj.optString("sourceUrl", "")
                        )
                    )
                }
            }
            _itemsFlow.value = list
            _storageError.value = null
        } catch (e: Exception) {
            android.util.Log.e("MediaItemRepository", "Unable to read persisted library; keeping current in-memory state", e)
            _storageError.value = STORAGE_READ_ERROR
        }
    }

    private fun savePersistedItems(items: List<MediaItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("filePath", item.filePath)
                put("durationMs", item.durationMs)
                put("originalDurationMs", item.originalDurationMs)
                put("speed", item.speed.toDouble())
                put("format", item.format)
                put("fileSize", item.fileSize)
                put("isVideo", item.isVideo)
                put("fileType", item.fileType.name)
                put("resolutionText", item.resolutionText)
                put("bitrateKbps", item.bitrateKbps)
                put("dateAdded", item.dateAdded)
                put("sourceUrl", item.sourceUrl)
            }
            array.put(obj)
        }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        val output = atomicStorageFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicStorageFile.finishWrite(output)
        } catch (e: Exception) {
            atomicStorageFile.failWrite(output)
            throw e
        }
    }

    suspend fun addItem(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val updated = _itemsFlow.value.toMutableList().apply {
                removeAll { it.id == item.id || it.filePath == item.filePath }
                add(0, item)
            }
            try {
                savePersistedItems(updated)
                _itemsFlow.value = updated
                _storageError.value = null
                true
            } catch (e: Exception) {
                android.util.Log.e("MediaItemRepository", "Unable to persist library addition", e)
                _storageError.value = STORAGE_WRITE_ERROR
                false
            }
        }
    }

    suspend fun deleteItem(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val updated = _itemsFlow.value.filterNot { it.id == item.id }
            try {
                savePersistedItems(updated)
                _itemsFlow.value = updated
                _storageError.value = null
            } catch (e: Exception) {
                android.util.Log.e("MediaItemRepository", "Unable to persist library deletion", e)
                _storageError.value = STORAGE_WRITE_ERROR
                return@withLock false
            }

            val file = File(item.filePath)
            if (file.exists() && !file.delete()) {
                android.util.Log.w("MediaItemRepository", "Removed library entry but could not delete its media file")
            }
            true
        }
    }

    private companion object {
        const val STORAGE_READ_ERROR = "تعذر قراءة سجل المكتبة؛ احتُفظ بملف السجل ولم تُمسح القائمة الحالية."
        const val STORAGE_WRITE_ERROR = "تعذر حفظ سجل المكتبة؛ لم تُطبّق التغييرات على القائمة الحالية."
    }

    /**
     * Extracts metadata from any user-selected local file Uri
     */
    suspend fun inspectLocalUri(uri: Uri): MediaItem? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var title = getFileNameFromUri(uri) ?: "ملف ميديا"
        var durationMs = 0L
        var hasVideo = false
        var mimeType = "*/*"
        var resolution = ""

        try {
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) { null }

            if (pfd != null) {
                pfd.use {
                    retriever.setDataSource(it.fileDescriptor)
                }
            } else {
                retriever.setDataSource(context, uri)
            }

            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: title

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durationStr?.toLongOrNull() ?: 0L

            hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != null
            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "*/*"

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (width != null && height != null) {
                resolution = "${height}p ($width x $height)"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val ext = title.substringAfterLast('.', "").lowercase()
        val detectedFileType = when {
            hasVideo || mimeType.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "avi") -> FileType.VIDEO
            mimeType.startsWith("audio/") || ext in listOf("mp3", "m4a", "wav", "flac", "aac") -> FileType.AUDIO
            ext == "pdf" -> FileType.DOCUMENT
            ext == "apk" -> FileType.APK
            ext in listOf("zip", "rar", "7z") -> FileType.ARCHIVE
            else -> FileType.OTHER
        }

        return@withContext MediaItem(
            id = UUID.randomUUID().toString(),
            title = title,
            filePath = "",
            durationMs = durationMs,
            originalDurationMs = durationMs,
            speed = 1.0f,
            format = if (detectedFileType == FileType.VIDEO) "MP4"
                     else if (ext == "wav") "WAV"
                     else if (ext == "m4a") "M4A"
                     else "MP3",
            fileSize = 0L,
            isVideo = hasVideo || detectedFileType == FileType.VIDEO,
            fileType = detectedFileType,
            resolutionText = resolution,
            sourceUrl = uri.toString()
        )
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        return name
    }

    /**
     * Opens file in external player, document reader, or APK installer
     */
    fun openFileIntent(item: MediaItem): Intent? {
        val file = File(item.filePath)
        if (!file.exists()) return null

        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)

        val ext = file.extension.lowercase()
        val mimeType = when (item.fileType) {
            FileType.VIDEO -> "video/*"
            FileType.AUDIO -> "audio/*"
            FileType.DOCUMENT -> if (ext == "pdf") "application/pdf" else "text/plain"
            FileType.APK -> "application/vnd.android.package-archive"
            FileType.IMAGE -> "image/*"
            FileType.ARCHIVE -> "application/zip"
            else -> "*/*"
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Share file to Telegram, WhatsApp, Bluetooth, or nearby devices
     */
    fun shareFileIntent(item: MediaItem): Intent? {
        val file = File(item.filePath)
        if (!file.exists()) return null

        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)

        return Intent(Intent.ACTION_SEND).apply {
            type = if (item.isVideo) "video/*" else if (item.fileType == FileType.AUDIO) "audio/*" else "*/*"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Publishes a file to Downloads using scoped storage on Android 10 and newer. */
    suspend fun exportToPublicDownloads(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            false
        } else {
            exportToMediaStoreDownloads(item)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun exportToMediaStoreDownloads(item: MediaItem): Boolean {
        val sourceFile = File(item.filePath)
        if (!sourceFile.isFile) return false

        val resolver = context.contentResolver
        val displayName = sourceFile.name
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(sourceFile.extension.lowercase())
            ?: when (item.fileType) {
                FileType.VIDEO -> "video/*"
                FileType.AUDIO -> "audio/*"
                FileType.DOCUMENT -> "application/pdf"
                FileType.APK -> "application/vnd.android.package-archive"
                FileType.IMAGE -> "image/*"
                FileType.ARCHIVE -> "application/zip"
                FileType.OTHER -> "application/octet-stream"
            }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/File Station"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destinationUri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {
            null
        } ?: return false

        var published = false
        try {
            val expectedLength = sourceFile.length()
            val copiedLength = FileInputStream(sourceFile).use { input ->
                resolver.openOutputStream(destinationUri, "w")?.use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                } ?: throw IllegalStateException("Cannot open the Downloads destination")
            }
            if (copiedLength != expectedLength) {
                throw IllegalStateException("Exported file length does not match its source")
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(destinationUri, publishValues, null, null) != 1) {
                throw IllegalStateException("Cannot publish the completed Downloads item")
            }
            published = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            if (!published) runCatching { resolver.delete(destinationUri, null, null) }
        }
    }

    /** Copies a file to a user-selected SAF document URI on Android 9 and older. */
    suspend fun exportToDocument(item: MediaItem, destinationUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val sourceFile = File(item.filePath)
            if (!sourceFile.isFile) return@withContext false
            val resolver = context.contentResolver
            var copied = false
            try {
                val expectedLength = sourceFile.length()
                val copiedLength = FileInputStream(sourceFile).use { input ->
                    resolver.openOutputStream(destinationUri, "w")?.use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    } ?: throw IllegalStateException("Cannot open the selected destination")
                }
                if (copiedLength != expectedLength) {
                    throw IllegalStateException("Exported file length does not match its source")
                }
                copied = true
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                if (!copied) runCatching { resolver.delete(destinationUri, null, null) }
            }
        }
}
