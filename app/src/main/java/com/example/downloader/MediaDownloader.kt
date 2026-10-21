package com.example.downloader

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import com.example.audio.AudioTranscoder
import com.example.audio.VideoTranscoder
import com.example.model.AudioBitrate
import com.example.model.AudioFormat
import com.example.model.DuplicateAction
import com.example.model.FileType
import com.example.model.MediaItem
import com.example.model.PlaylistItem
import com.example.model.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

private val RESUME_KEY_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
private val CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
private val UNSATISFIED_CONTENT_RANGE_PATTERN = Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE)

class MediaDownloader(
    private val context: Context,
    private val audioTranscoder: AudioTranscoder
) {

    private val videoTranscoder = VideoTranscoder(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private data class DownloadCheckpoint(
        val completed: Boolean,
        val totalBytes: Long,
        val eTag: String,
        val lastModified: String,
        val metadata: DirectDownloadMetadata
    )

    fun resumablePartialFile(resumeKey: String): File =
        File(File(context.filesDir, "audio_downloads"), ".resume-$resumeKey.part")

    private fun resumableStateFile(resumeKey: String): File =
        File(File(context.filesDir, "audio_downloads"), ".resume-$resumeKey.json")

    fun cleanupResumableDownload(resumeKey: String) {
        if (!RESUME_KEY_PATTERN.matches(resumeKey)) return
        resumablePartialFile(resumeKey).delete()
        AtomicFile(resumableStateFile(resumeKey)).delete()
    }

    private fun readCheckpoint(file: File): DownloadCheckpoint? = runCatching {
        val json = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        DownloadCheckpoint(
            completed = json.optBoolean("completed", false),
            totalBytes = json.optLong("totalBytes", -1L),
            eTag = json.optString("eTag", ""),
            lastModified = json.optString("lastModified", ""),
            metadata = DirectDownloadMetadata(
                fileName = json.getString("fileName"),
                mimeType = json.getString("mimeType"),
                extension = json.getString("extension"),
                fileType = FileType.valueOf(json.getString("fileType"))
            )
        )
    }.getOrNull()

    private fun writeCheckpoint(file: File, checkpoint: DownloadCheckpoint) {
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            val json = JSONObject().apply {
                put("completed", checkpoint.completed)
                put("totalBytes", checkpoint.totalBytes)
                put("eTag", checkpoint.eTag)
                put("lastModified", checkpoint.lastModified)
                put("fileName", checkpoint.metadata.fileName)
                put("mimeType", checkpoint.metadata.mimeType)
                put("extension", checkpoint.metadata.extension)
                put("fileType", checkpoint.metadata.fileType.name)
            }
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun readFilePrefix(file: File, maximumBytes: Int): ByteArray =
        file.inputStream().use { input ->
            val buffer = ByteArray(maximumBytes)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read <= 0) break
                count += read
            }
            buffer.copyOf(count)
        }

    private fun contentRangeStart(header: String?): Long? =
        CONTENT_RANGE_PATTERN.find(header.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun contentRangeEnd(header: String?): Long? =
        CONTENT_RANGE_PATTERN.find(header.orEmpty())?.groupValues?.getOrNull(2)?.toLongOrNull()

    private fun contentRangeTotal(header: String?): Long? =
        CONTENT_RANGE_PATTERN.find(header.orEmpty())?.groupValues?.getOrNull(3)?.takeIf { it != "*" }?.toLongOrNull()

    private fun unsatisfiedContentRangeTotal(header: String?): Long? =
        UNSATISFIED_CONTENT_RANGE_PATTERN.find(header.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun downloadToResumeFile(
        url: String,
        item: PlaylistItem,
        targetFormat: AudioFormat,
        outputExtension: String,
        destination: File,
        checkpointFile: File,
        checkpoint: DownloadCheckpoint?,
        onDownloadProgress: (Float, Long, Long) -> Unit
    ): DownloadCheckpoint? = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        var offset = destination.length()
        var prior = checkpoint
        if (prior != null && offset > 0L && prior.totalBytes > 0L && offset == prior.totalBytes) {
            val completed = prior.copy(completed = true)
            writeCheckpoint(checkpointFile, completed)
            return@withContext completed
        }
        if (prior != null && prior.totalBytes > 0L && offset > prior.totalBytes) {
            destination.delete()
            AtomicFile(checkpointFile).delete()
            offset = 0L
            prior = null
        }
        if (offset > 0L && prior == null) {
            destination.delete()
            AtomicFile(checkpointFile).delete()
            return@withContext null
        }
        if (offset > 0L && prior != null && prior.eTag.isBlank() && prior.lastModified.isBlank()) {
            destination.delete()
            AtomicFile(checkpointFile).delete()
            return@withContext null
        }
        if (offset <= 0L || prior == null) {
            destination.delete()
            AtomicFile(checkpointFile).delete()
            offset = 0L
            prior = null
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            // Range offsets must refer to the same uncompressed byte representation.
            .header("Accept-Encoding", "identity")
            .get()
        if (offset > 0L) {
            requestBuilder.header("Range", "bytes=$offset-")
            val validator = prior?.eTag?.takeIf { it.isNotBlank() }
                ?: prior?.lastModified?.takeIf { it.isNotBlank() }
            if (!validator.isNullOrBlank()) requestBuilder.header("If-Range", validator)
        }
        val call = httpClient.newCall(requestBuilder.build())
        val cancellationHandle = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                val responseETag = response.header("ETag").orEmpty()
                val responseModified = response.header("Last-Modified").orEmpty()
                val validatorsMatch = prior != null &&
                    (prior.eTag.isBlank() || responseETag.isBlank() || prior.eTag == responseETag) &&
                    (prior.lastModified.isBlank() || responseModified.isBlank() || prior.lastModified == responseModified)
                if (response.code == 416 && offset > 0L && prior != null && validatorsMatch &&
                    unsatisfiedContentRangeTotal(response.header("Content-Range")) == offset) {
                    val completed = prior.copy(completed = true, totalBytes = offset)
                    writeCheckpoint(checkpointFile, completed)
                    return@withContext completed
                }
                val body = response.body ?: return@withContext null
                if (!response.isSuccessful) return@withContext null
                val contentRange = response.header("Content-Range")
                val rangeStart = contentRangeStart(contentRange)
                val rangeEnd = contentRangeEnd(contentRange)
                val rangeTotal = contentRangeTotal(contentRange)
                val append = offset > 0L && response.code == 206
                if (response.code == 206 && (rangeStart == null || rangeEnd == null ||
                        rangeStart != (if (offset > 0L) offset else 0L) || rangeEnd < rangeStart)) {
                    return@withContext null
                }
                if (offset > 0L && !append) {
                    // The server ignored Range or the If-Range validator changed. Never mix
                    // bytes from two representations or silently restart a supposedly resumed task.
                    destination.delete()
                    AtomicFile(checkpointFile).delete()
                    return@withContext null
                }
                if (append && rangeTotal != null && rangeEnd != null && rangeEnd >= rangeTotal) return@withContext null
                if (append && prior?.totalBytes?.let { it > 0L && rangeTotal != null && it != rangeTotal } == true) {
                    return@withContext null
                }
                if (append && prior != null) {
                    if (prior.eTag.isNotBlank() && responseETag.isNotBlank() && prior.eTag != responseETag) {
                        return@withContext null
                    }
                    if (prior.lastModified.isNotBlank() && responseModified.isNotBlank() && prior.lastModified != responseModified) {
                        return@withContext null
                    }
                }
                val totalBytes = rangeTotal
                    ?: if (append) prior?.totalBytes ?: -1L else body.contentLength()
                val expectedResponseBytes = if (append && rangeEnd != null) {
                    rangeEnd - offset + 1L
                } else {
                    body.contentLength()
                }
                val responseUrl = response.request.url.toString()
                val metadata: DirectDownloadMetadata
                val input = body.byteStream().buffered()
                val prefix: ByteArray
                if (append) {
                    prefix = readFilePrefix(destination, 1024)
                    metadata = prior?.metadata ?: return@withContext null
                    val revalidated = DirectDownloadMetadataResolver.validateResponse(
                        statusCode = response.code,
                        contentTypeHeader = response.header("Content-Type"),
                        contentDisposition = response.header("Content-Disposition"),
                        responseUrl = responseUrl,
                        fallbackFileName = item.sourceFileName,
                        prefix = prefix
                    )
                    if (revalidated == null || !revalidated.extension.equals(metadata.extension, ignoreCase = true)) return@withContext null
                } else {
                    val prefixBuffer = ByteArray(1024)
                    var prefixSize = 0
                    while (prefixSize < prefixBuffer.size) {
                        job?.ensureActive()
                        val read = input.read(prefixBuffer, prefixSize, prefixBuffer.size - prefixSize)
                        if (read <= 0) break
                        prefixSize += read
                    }
                    if (prefixSize == 0) return@withContext null
                    prefix = prefixBuffer.copyOf(prefixSize)
                    val inferred = DirectDownloadMetadataResolver.validateResponse(
                        statusCode = response.code,
                        contentTypeHeader = response.header("Content-Type"),
                        contentDisposition = response.header("Content-Disposition"),
                        responseUrl = responseUrl,
                        fallbackFileName = item.sourceFileName,
                        prefix = prefix
                    ) ?: return@withContext null
                    val actualSourceType = inferred.fileType.takeIf { it != FileType.OTHER } ?: item.fileType
                    val targetMatchesSource = when {
                        targetFormat == AudioFormat.ORIGINAL -> inferred.extension.equals(outputExtension, ignoreCase = true)
                        targetFormat in setOf(AudioFormat.MP4, AudioFormat.MKV, AudioFormat.WEBM) -> actualSourceType == FileType.VIDEO
                        targetFormat in setOf(AudioFormat.MP3, AudioFormat.M4A, AudioFormat.WAV) ->
                            actualSourceType == FileType.AUDIO || actualSourceType == FileType.VIDEO
                        else -> false
                    }
                    if (!targetMatchesSource) return@withContext null
                    metadata = inferred.copy(fileType = actualSourceType)
                }

                val currentCheckpoint = (prior ?: DownloadCheckpoint(
                    completed = false,
                    totalBytes = totalBytes,
                    eTag = response.header("ETag").orEmpty(),
                    lastModified = response.header("Last-Modified").orEmpty(),
                    metadata = metadata
                )).copy(
                    completed = false,
                    totalBytes = totalBytes,
                    eTag = response.header("ETag")?.takeIf { it.isNotBlank() } ?: prior?.eTag.orEmpty(),
                    lastModified = response.header("Last-Modified")?.takeIf { it.isNotBlank() } ?: prior?.lastModified.orEmpty(),
                    metadata = metadata
                )
                writeCheckpoint(checkpointFile, currentCheckpoint)
                val copiedAtStart = if (append) offset else 0L
                if (totalBytes > 0L) {
                    onDownloadProgress((copiedAtStart / totalBytes.toFloat()).coerceIn(0f, 1f), copiedAtStart, totalBytes)
                }
                var segmentBytesCopied = 0L
                FileOutputStream(destination, append).use { output ->
                    try {
                        if (!append) output.write(prefix)
                        var copied = copiedAtStart + if (append) 0L else prefix.size.toLong()
                        segmentBytesCopied = if (append) 0L else prefix.size.toLong()
                        if (!append && totalBytes > 0L) {
                            onDownloadProgress((copied / totalBytes.toFloat()).coerceIn(0f, 1f), copied, totalBytes)
                        }
                        val buffer = ByteArray(16_384)
                        var bytesSinceSync = segmentBytesCopied
                        while (true) {
                            job?.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            segmentBytesCopied += read
                            bytesSinceSync += read
                            if (bytesSinceSync >= 1_048_576L) {
                                output.fd.sync()
                                bytesSinceSync = 0L
                            }
                            if (totalBytes > 0L) {
                                onDownloadProgress((copied / totalBytes.toFloat()).coerceIn(0f, 1f), copied, totalBytes)
                            }
                        }
                    } finally {
                        runCatching { output.fd.sync() }
                    }
                }
                if (expectedResponseBytes > 0L && segmentBytesCopied != expectedResponseBytes) return@withContext null
                val completedFileBytes = destination.length()
                if (totalBytes > 0L && completedFileBytes != totalBytes) return@withContext null
                val finished = currentCheckpoint.copy(completed = true)
                writeCheckpoint(checkpointFile, finished)
                finished
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            job?.ensureActive()
            throw error
        } finally {
            cancellationHandle?.dispose()
        }
    }

    data class ExtractedMediaInfo(
        val isPlaylist: Boolean,
        val playlistTitle: String = "",
        val items: List<PlaylistItem> = emptyList(),
        val directMediaUrl: String? = null
    )

    /**
     * Inspects URL and returns real playlist or video/file metadata
     */
    suspend fun inspectUrl(url: String): ExtractedMediaInfo = withContext(Dispatchers.IO) {
        val trimmed = url.trim()

        if (isPlaylistUrl(trimmed)) {
            return@withContext parsePlaylist(trimmed)
        } else if (isYouTubeVideoUrl(trimmed)) {
            val singleItem = parseYouTubeVideo(trimmed)
            return@withContext ExtractedMediaInfo(
                isPlaylist = false,
                items = listOf(singleItem)
            )
        } else {
            // Direct file or general website link (Video, Audio, PDF, APK, Archive, etc.)
            val directInfo = parseDirectOrWebLink(trimmed)
            return@withContext ExtractedMediaInfo(
                isPlaylist = false,
                items = listOf(directInfo),
                directMediaUrl = trimmed
            )
        }
    }

    private fun isPlaylistUrl(url: String): Boolean {
        return url.contains("list=") || url.contains("/playlist")
    }

    private fun isYouTubeVideoUrl(url: String): Boolean {
        return url.contains("youtube.com/watch") || url.contains("youtu.be/") || url.contains("youtube.com/shorts")
    }

    private fun extractYouTubeVideoId(url: String): String {
        val pattern = Pattern.compile("(?:v=|youtu\\.be/|shorts/)([a-zA-Z0-9_-]{11})")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    private fun extractPlaylistId(url: String): String {
        val pattern = Pattern.compile("list=([a-zA-Z0-9_-]+)")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    /**
     * Extracts REAL YouTube metadata (actual duration in seconds, real title, author, thumbnail)
     */
    private suspend fun parseYouTubeVideo(url: String): PlaylistItem {
        val videoId = extractYouTubeVideoId(url)
        var title = "فيديو يوتيوب $videoId"
        var author = "YouTube"
        var durationSeconds = 0L
        var resolvedStreamUrl = ""
        var resolvedStreamMimeType = ""

        // 1. Try public Piped / Invidious API for accurate duration and direct stream links
        val invidiousEndpoints = listOf(
            "https://inv.tux.pizza/api/v1/videos/$videoId",
            "https://invidious.nerdvpn.de/api/v1/videos/$videoId",
            "https://pipedapi.kavin.rocks/streams/$videoId"
        )

        for (endpoint in invidiousEndpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", userAgent)
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val jsonStr = resp.body?.string() ?: ""
                    val json = JSONObject(jsonStr)
                    title = json.optString("title", title)
                    author = json.optString("author", json.optString("uploader", author))
                    val durSec = json.optLong("lengthSeconds", json.optLong("duration", 0L))
                    if (durSec > 0) {
                        durationSeconds = durSec
                    }
                    // Check format streams
                    val formatStreams = json.optJSONArray("formatStreams")
                    if (formatStreams != null && formatStreams.length() > 0) {
                        val firstStream = formatStreams.getJSONObject(0)
                        resolvedStreamUrl = firstStream.optString("url", "")
                        resolvedStreamMimeType = firstStream.optString("mimeType", "")
                    }
                    if (durationSeconds > 0) break
                }
            } catch (_: Exception) {}
        }

        // 2. Direct YouTube Web Scraping if duration was not retrieved yet
        if (durationSeconds <= 0L) {
            try {
                val watchUrl = "https://www.youtube.com/watch?v=$videoId"
                val req = Request.Builder()
                    .url(watchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Language", "ar,en;q=0.9")
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""

                    // Extract exact lengthSeconds: "lengthSeconds":"185"
                    val durMatcher = Pattern.compile("\"lengthSeconds\"\\s*:\\s*\"(\\d+)\"").matcher(html)
                    if (durMatcher.find()) {
                        durationSeconds = durMatcher.group(1)?.toLongOrNull() ?: 0L
                    }

                    // Extract title
                    val titleMatcher = Pattern.compile("<title>([^<]+)</title>").matcher(html)
                    if (titleMatcher.find()) {
                        val rawTitle = titleMatcher.group(1)?.replace("- YouTube", "")?.trim() ?: ""
                        if (rawTitle.isNotEmpty()) title = rawTitle
                    }

                    // Extract author
                    val authorMatcher = Pattern.compile("\"ownerChannelName\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                    if (authorMatcher.find()) {
                        author = authorMatcher.group(1) ?: author
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback to oEmbed for title & author
        if (title.contains("فيديو يوتيوب")) {
            try {
                val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
                val req = Request.Builder().url(oembedUrl).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    title = json.optString("title", title)
                    author = json.optString("author_name", author)
                }
            } catch (_: Exception) {}
        }

        val durationFormatted = formatSecondsToTime(durationSeconds)

        return PlaylistItem(
            id = videoId.ifEmpty { UUID.randomUUID().toString() },
            title = title,
            author = author,
            durationText = durationFormatted,
            durationSeconds = durationSeconds,
            thumbnailUrl = if (videoId.isNotEmpty()) "https://img.youtube.com/vi/$videoId/hqdefault.jpg" else "",
            url = url,
            isSelected = true,
            isVideo = true,
            fileType = FileType.VIDEO,
            directStreamUrl = resolvedStreamUrl,
            sourceMimeType = resolvedStreamMimeType
        )
    }

    /**
     * Inspects direct download links or general websites for any file type
     */
    private suspend fun parseDirectOrWebLink(url: String): PlaylistItem {
        var contentLength = 0L
        var metadata = DirectDownloadMetadataResolver.fromHeaders(
            contentTypeHeader = null,
            contentDisposition = null,
            url = url
        )

        try {
            val headReq = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .head()
                .build()
            httpClient.newCall(headReq).execute().use { response ->
                if (response.isSuccessful) {
                    val contentType = response.header("Content-Type")
                    val disposition = response.header("Content-Disposition")
                    contentLength = response.header("Content-Length")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    metadata = DirectDownloadMetadataResolver.fromHeaders(
                        contentTypeHeader = contentType,
                        contentDisposition = disposition,
                        url = response.request.url.toString()
                    )
                }
            }
        } catch (_: Exception) {
            // HEAD may be unsupported. The GET response is validated before any output is saved.
        }

        // Some hosts suppress filenames and report only application/octet-stream on HEAD.
        // A one-byte range probe can still reveal the real Content-Type/Content-Disposition
        // needed to plan an original-format destination before conflict handling.
        val isKnownWebPayload = metadata.mimeType == "text/html" ||
            metadata.mimeType == "application/xhtml+xml" ||
            metadata.mimeType == "application/json" || metadata.mimeType == "text/json" ||
            metadata.mimeType.endsWith("+json")
        if (metadata.extension == "bin" && !isKnownWebPayload) {
            try {
                val probeRequest = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()
                httpClient.newCall(probeRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        metadata = DirectDownloadMetadataResolver.fromHeaders(
                            contentTypeHeader = response.header("Content-Type"),
                            contentDisposition = response.header("Content-Disposition"),
                            url = response.request.url.toString(),
                            fallbackFileName = metadata.fileName
                        )
                        val rangeTotal = response.header("Content-Range")
                            ?.substringAfterLast('/')?.toLongOrNull()
                        val fullResponseLength = if (response.code == 206) null else {
                            response.header("Content-Length")?.toLongOrNull()?.coerceAtLeast(0L)
                        }
                        contentLength = rangeTotal ?: fullResponseLength ?: contentLength
                    }
                }
            } catch (_: Exception) {
                // Keep the conservative `.bin` fallback; the actual GET is still validated.
            }
        }

        val cleanTitle = metadata.fileName.substringBeforeLast('.')
            .ifEmpty { "ملف من رابط خارجي" }
        val isVideo = metadata.fileType == FileType.VIDEO
        return PlaylistItem(
            id = UUID.randomUUID().toString(),
            title = cleanTitle,
            author = "رابط مباشر / موقع خارجي",
            durationText = if (metadata.fileType == FileType.VIDEO || metadata.fileType == FileType.AUDIO) "جاري القياس" else "--:--",
            durationSeconds = 0,
            thumbnailUrl = "",
            url = url,
            isSelected = true,
            isVideo = isVideo,
            fileType = metadata.fileType,
            fileSizeEstimate = contentLength,
            sourceMimeType = metadata.mimeType,
            sourceFileName = metadata.fileName
        )
    }

    private suspend fun parsePlaylist(url: String): ExtractedMediaInfo {
        val playlistId = extractPlaylistId(url)
        val items = mutableListOf<PlaylistItem>()
        var playlistTitle = "قائمة تشغيل $playlistId"

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val bodyHtml = resp.body?.string() ?: ""

                val titleMatcher = Pattern.compile("<title>([^<]+)</title>").matcher(bodyHtml)
                if (titleMatcher.find()) {
                    playlistTitle = titleMatcher.group(1)?.replace("- YouTube", "")?.trim() ?: playlistTitle
                }

                val videoIdPattern = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                val matcher = videoIdPattern.matcher(bodyHtml)
                val foundIds = linkedSetOf<String>()
                while (matcher.find() && foundIds.size < 50) {
                    val id = matcher.group(1) ?: continue
                    foundIds.add(id)
                }

                for ((index, id) in foundIds.withIndex()) {
                    items.add(
                        PlaylistItem(
                            id = id,
                            title = "مقطع #${index + 1} من $playlistTitle",
                            author = "",
                            durationText = "--:--",
                            durationSeconds = 0L,
                            thumbnailUrl = "https://img.youtube.com/vi/$id/hqdefault.jpg",
                            url = "https://www.youtube.com/watch?v=$id",
                            isSelected = true,
                            isVideo = true,
                            fileType = FileType.VIDEO
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ExtractedMediaInfo(
            isPlaylist = true,
            playlistTitle = playlistTitle,
            items = items
        )
    }

    /**
     * Checks if a file already exists with the intended target name
     */
    fun checkFileExists(targetFileName: String): File? {
        val downloadsDir = File(context.filesDir, "audio_downloads")
        val file = File(downloadsDir, targetFileName)
        return file.takeIf { it.isFile }
    }

    fun existingOutputFileNames(): Set<String> {
        val downloadsDir = File(context.filesDir, "audio_downloads")
        return downloadsDir.listFiles()
            ?.filter { it.isFile }
            ?.mapTo(mutableSetOf()) { it.name }
            ?: emptySet()
    }

    /**
     * Resolves unique file name if download anyway is chosen
     */
    private fun getUniqueFileName(baseDir: File, baseName: String, extension: String): File {
        var file = File(baseDir, "$baseName.$extension")
        var counter = 1
        while (file.exists()) {
            file = File(baseDir, "$baseName ($counter).$extension")
            counter++
        }
        return file
    }

    private fun installDownloadedSource(source: File, destination: File, keepSource: Boolean): Boolean {
        if (!source.isFile || source.length() <= 0L) return false
        return try {
            if (!keepSource) {
                source.renameTo(destination)
            } else {
                source.inputStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        output.fd.sync()
                    }
                }
                destination.isFile && destination.length() == source.length()
            }
        } catch (error: Exception) {
            error.printStackTrace()
            false
        }
    }

    private fun publishStagedOutput(stagedFile: File, destination: File): Boolean {
        if (!stagedFile.isFile || stagedFile.length() <= 0L) return false
        return try {
            // Same-directory POSIX rename atomically replaces destination on Android/Linux.
            android.system.Os.rename(stagedFile.absolutePath, destination.absolutePath)
            destination.isFile && destination.length() > 0L
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Downloads any media/file from any link, applies speed/format processing,
     * and handles duplicate conflicts.
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun downloadAndProcessItem(
        item: PlaylistItem,
        targetFormat: AudioFormat,
        targetSpeed: Float,
        targetQuality: VideoQuality = VideoQuality.Q720P,
        targetBitrate: AudioBitrate = AudioBitrate.B128,
        duplicateAction: DuplicateAction = DuplicateAction.DOWNLOAD_ANYWAY,
        outputFileName: String? = null,
        resumeKey: String? = null,
        preservePartialOnCancellation: Boolean = false,
        onDownloadProgress: (progress: Float, downloaded: Long, total: Long) -> Unit,
        onSpeedProgress: (progress: Float, stage: String) -> Unit
    ): MediaItem? = withContext(Dispatchers.IO) {
        val coroutineJob = currentCoroutineContext()[Job]
        if (resumeKey != null && !RESUME_KEY_PATTERN.matches(resumeKey)) return@withContext null
        if (duplicateAction == DuplicateAction.CANCEL) {
            return@withContext null
        }

        val downloadsDir = File(context.filesDir, "audio_downloads").apply { mkdirs() }
        val cleanTitle = item.title.replace(Regex("[^a-zA-Z0-9أ-ي_\\-\\s]"), "").trim().ifEmpty { "ملف_${System.currentTimeMillis()}" }

        val isOriginalFormat = targetFormat == AudioFormat.ORIGINAL
        val requestsVideoContainer = targetFormat == AudioFormat.MP4 ||
            targetFormat == AudioFormat.MKV || targetFormat == AudioFormat.WEBM
        val requestsAudioContainer = targetFormat == AudioFormat.MP3 ||
            targetFormat == AudioFormat.M4A || targetFormat == AudioFormat.WAV
        // Reject known source/target mismatches early. Unknown direct links are checked against
        // the actual GET response before the file is copied or transcoded.
        if (!isOriginalFormat && requestsVideoContainer && item.fileType == FileType.AUDIO) {
            return@withContext null
        }
        if (!isOriginalFormat && requestsAudioContainer &&
            item.fileType !in setOf(FileType.AUDIO, FileType.VIDEO, FileType.OTHER)) {
            return@withContext null
        }
        val speedTag = if (!isOriginalFormat && kotlin.math.abs(targetSpeed - 1.0f) > 0.01f) "_${targetSpeed}x" else ""
        val qualityTag = if (!isOriginalFormat && requestsVideoContainer) "_${targetQuality.height}p" else ""
        val outputExt = if (isOriginalFormat) {
            DirectDownloadMetadataResolver.extensionFrom(
                contentTypeHeader = item.sourceMimeType,
                fileName = item.sourceFileName,
                url = item.directStreamUrl.ifEmpty { item.url }
            )
        } else targetFormat.extension
        if (outputExt.isBlank()) return@withContext null
        val baseFileName = "$cleanTitle$qualityTag$speedTag"
        val plannedFileName = outputFileName?.let { name ->
            name.takeIf { File(it).name == it && it.endsWith(".$outputExt", ignoreCase = true) }
        }
        if (outputFileName != null && plannedFileName == null) return@withContext null
        val requestedOutputFile = if (plannedFileName != null) {
            val plannedFile = File(downloadsDir, plannedFileName)
            if (duplicateAction == DuplicateAction.DOWNLOAD_ANYWAY && plannedFile.exists()) {
                getUniqueFileName(
                    downloadsDir,
                    plannedFile.nameWithoutExtension,
                    outputExt
                )
            } else plannedFile
        } else if (duplicateAction == DuplicateAction.REPLACE_EXISTING) {
            File(downloadsDir, "$baseFileName.$outputExt")
        } else {
            getUniqueFileName(downloadsDir, baseFileName, outputExt)
        }
        // Keep an existing file intact throughout download and conversion. The staged file
        // retains the real extension so the appropriate muxer/encoder still selects correctly.
        val targetOutputFile = if (duplicateAction == DuplicateAction.REPLACE_EXISTING) {
            File(downloadsDir, ".${requestedOutputFile.nameWithoutExtension}.${UUID.randomUUID()}.pending.$outputExt")
        } else requestedOutputFile

        val tempDownloadFile = resumeKey?.let(::resumablePartialFile)
            ?: File(downloadsDir, "temp_download_${UUID.randomUUID()}.tmp")
        val checkpointFile = resumeKey?.let(::resumableStateFile)
        var resumeCheckpoint = checkpointFile?.let(::readCheckpoint)
        if (resumeKey != null && tempDownloadFile.isFile && tempDownloadFile.length() > 0L && resumeCheckpoint == null) {
            cleanupResumableDownload(resumeKey)
            return@withContext null
        }
        if (resumeKey != null && (resumeCheckpoint == null || !tempDownloadFile.isFile)) {
            tempDownloadFile.delete()
            checkpointFile?.let { AtomicFile(it).delete() }
            resumeCheckpoint = null
        }

        try {
            // 1. Resolve stream or direct download URL
            var streamUrl = item.directStreamUrl.ifEmpty { item.url }

            // If it's a YouTube video without resolved stream yet, try resolving public streaming link
            if (isYouTubeVideoUrl(streamUrl)) {
                val videoId = extractYouTubeVideoId(streamUrl)
                val resolved = resolveYouTubeStreamUrl(videoId)
                if (resolved.isNotEmpty()) {
                    streamUrl = resolved
                }
            }

            val checkpointTotalBytes = resumeCheckpoint?.totalBytes ?: -1L
            val checkpointIsComplete = resumeCheckpoint?.completed == true && tempDownloadFile.isFile &&
                tempDownloadFile.length() > 0L &&
                (checkpointTotalBytes <= 0L || tempDownloadFile.length() == checkpointTotalBytes)
            var downloadSuccessful = checkpointIsComplete
            var downloadMetadata: DirectDownloadMetadata? = if (checkpointIsComplete) resumeCheckpoint?.metadata else null
            var originalDurationMs = item.durationSeconds * 1000L
            var finalDurationMs = originalDurationMs
            var finalResolution = ""
            var sourceBitrateKbps: Int? = null
            var finalVideoInfo: VideoTranscoder.VideoInfo? = null

            // Download only a successful, non-empty response whose headers and leading bytes
            // identify a real file. This prevents HTML/JSON error pages from masquerading as media.
            if (!downloadSuccessful && streamUrl.isNotEmpty() && !isYouTubeVideoUrl(streamUrl)) {
                try {
                    if (resumeKey != null && checkpointFile != null) {
                        resumeCheckpoint = downloadToResumeFile(
                            url = streamUrl,
                            item = item,
                            targetFormat = targetFormat,
                            outputExtension = outputExt,
                            destination = tempDownloadFile,
                            checkpointFile = checkpointFile,
                            checkpoint = resumeCheckpoint,
                            onDownloadProgress = onDownloadProgress
                        )
                        downloadMetadata = resumeCheckpoint?.metadata
                        val resumedTotalBytes = resumeCheckpoint?.totalBytes ?: -1L
                        downloadSuccessful = resumeCheckpoint?.completed == true && tempDownloadFile.isFile &&
                            tempDownloadFile.length() > 0L &&
                            (resumedTotalBytes <= 0L || tempDownloadFile.length() == resumedTotalBytes)
                    } else {
                        val request = Request.Builder()
                            .url(streamUrl)
                            .header("User-Agent", userAgent)
                            .get()
                            .build()
                        val call = httpClient.newCall(request)
                        val cancellationHandle = coroutineJob?.invokeOnCompletion(
                            onCancelling = true,
                            invokeImmediately = true
                        ) { cause ->
                            if (cause is CancellationException) call.cancel()
                        }
                        try {
                            call.execute().use { response ->
                                val body = response.body
                                if (response.isSuccessful && body != null) {
                                    val totalBytes = body.contentLength()
                                    body.byteStream().buffered().use { input ->
                                        val prefixBuffer = ByteArray(1024)
                                        var prefixSize = 0
                                        while (prefixSize < prefixBuffer.size) {
                                            coroutineJob?.ensureActive()
                                            val read = input.read(prefixBuffer, prefixSize, prefixBuffer.size - prefixSize)
                                            if (read <= 0) break
                                            prefixSize += read
                                        }
                                        if (prefixSize > 0) {
                                            val metadata = DirectDownloadMetadataResolver.validateResponse(
                                                statusCode = response.code,
                                                contentTypeHeader = response.header("Content-Type"),
                                                contentDisposition = response.header("Content-Disposition"),
                                                responseUrl = response.request.url.toString(),
                                                fallbackFileName = item.sourceFileName,
                                                prefix = prefixBuffer.copyOf(prefixSize)
                                            )
                                            val actualSourceType = metadata?.fileType
                                                ?.takeIf { it != FileType.OTHER } ?: item.fileType
                                            val targetMatchesSource = when {
                                                metadata == null -> false
                                                isOriginalFormat -> metadata.extension.equals(outputExt, ignoreCase = true)
                                                requestsVideoContainer -> actualSourceType == FileType.VIDEO
                                                requestsAudioContainer -> actualSourceType == FileType.AUDIO || actualSourceType == FileType.VIDEO
                                                else -> false
                                            }
                                            if (metadata != null && targetMatchesSource) {
                                                downloadMetadata = metadata.copy(fileType = actualSourceType)
                                                FileOutputStream(tempDownloadFile).use { output ->
                                                    output.write(prefixBuffer, 0, prefixSize)
                                                    var bytesCopied = prefixSize.toLong()
                                                    if (totalBytes > 0L) onDownloadProgress(
                                                        (bytesCopied.toFloat() / totalBytes).coerceIn(0f, 1f), bytesCopied, totalBytes
                                                    )
                                                    val buffer = ByteArray(16_384)
                                                    while (true) {
                                                        coroutineJob?.ensureActive()
                                                        val read = input.read(buffer)
                                                        if (read < 0) break
                                                        output.write(buffer, 0, read)
                                                        bytesCopied += read
                                                        if (totalBytes > 0L) onDownloadProgress(
                                                            (bytesCopied.toFloat() / totalBytes).coerceIn(0f, 1f), bytesCopied, totalBytes
                                                        )
                                                    }
                                                    output.fd.sync()
                                                }
                                                downloadSuccessful = tempDownloadFile.isFile && tempDownloadFile.length() > 0L
                                            } else {
                                                onSpeedProgress(
                                                    0.02f,
                                                    if (metadata == null) "رُفضت استجابة HTML/JSON أو ملف غير صالح."
                                                    else "نوع الملف أو امتداده لا يطابق الخيار المحدد."
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            cancellationHandle?.dispose()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    coroutineJob?.ensureActive()
                    error.printStackTrace()
                }
            }

            // Never substitute generated content for a failed download.
            if (!downloadSuccessful) {
                if (resumeKey == null) tempDownloadFile.delete()
                if (targetOutputFile != requestedOutputFile) targetOutputFile.delete()
                return@withContext null
            }
            val actualSourceType = downloadMetadata?.fileType ?: item.fileType
            val isVideoDownload = actualSourceType == FileType.VIDEO &&
                (requestsVideoContainer || isOriginalFormat)

            // Inspect downloaded media metadata when available; non-media originals are allowed.
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempDownloadFile.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val parsedDur = durStr?.toLongOrNull() ?: 0L
                if (parsedDur > 0) {
                    originalDurationMs = parsedDur
                    finalDurationMs = parsedDur
                }
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val parsedWidth = width?.toIntOrNull()
                val parsedHeight = height?.toIntOrNull()
                if (isVideoDownload && parsedWidth != null && parsedHeight != null) {
                    finalResolution = "${minOf(parsedWidth, parsedHeight)}p (${parsedWidth} x ${parsedHeight})"
                }
                sourceBitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull()?.takeIf { it > 0 }?.div(1000)
            } catch (_: Exception) {
                // Documents, archives, and images have no media metadata to extract.
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            if (isVideoDownload && !isOriginalFormat) {
                val videoInfo = videoTranscoder.inspectVideo(tempDownloadFile)
                if (videoInfo == null) {
                    onSpeedProgress(0.1f, "تعذر التحقق من مسار الفيديو؛ أُلغي إخراج الملف.")
                    if (resumeKey == null) tempDownloadFile.delete()
                    return@withContext null
                }
                finalResolution = "${minOf(videoInfo.width, videoInfo.height)}p (${videoInfo.width} x ${videoInfo.height})"
                if (sourceBitrateKbps == null && videoInfo.bitRate > 0) {
                    sourceBitrateKbps = videoInfo.bitRate / 1000
                }
            }

            // 2. Re-encode video through Android's decoder/encoder for the requested size and speed.
            var audioOutputInfo: AudioTranscoder.AudioOutputInfo? = null
            if (isOriginalFormat) {
                val moved = installDownloadedSource(tempDownloadFile, targetOutputFile, keepSource = resumeKey != null)
                if (!moved || !targetOutputFile.isFile || targetOutputFile.length() <= 0L) {
                    if (resumeKey == null) tempDownloadFile.delete()
                    targetOutputFile.delete()
                    return@withContext null
                }
            } else if (isVideoDownload) {
                if (targetFormat == AudioFormat.WEBM) {
                    val sourceInfo = videoTranscoder.inspectVideo(tempDownloadFile)
                    val requestedShortSide = sourceInfo?.let { minOf(it.width, it.height, targetQuality.height) } ?: 0
                    if (sourceInfo == null || sourceInfo.width <= 0 || sourceInfo.height <= 0 ||
                        kotlin.math.abs(targetSpeed - 1.0f) > 0.01f ||
                        minOf(sourceInfo.width, sourceInfo.height) > targetQuality.height) {
                        onSpeedProgress(0.1f, "إعادة الترميز إلى WebM غير مدعومة؛ يتطلب WebM الحالي نسخ مسار المصدر دون تغيير الدقة أو السرعة.")
                        if (resumeKey == null) tempDownloadFile.delete()
                        return@withContext null
                    }
                    onSpeedProgress(0.1f, "إعادة تعبئة مسار المصدر في حاوية WebM دون تغيير الدقة أو السرعة (حد الدقة ${requestedShortSide}px)...")
                    val remuxSuccess = videoTranscoder.remuxVideo(
                        sourceFile = tempDownloadFile,
                        outputFile = targetOutputFile,
                        onProgress = onSpeedProgress
                    )
                    if (resumeKey == null) tempDownloadFile.delete()
                    if (!remuxSuccess || !targetOutputFile.isFile || targetOutputFile.length() <= 0L) {
                        targetOutputFile.delete()
                        return@withContext null
                    }
                    finalVideoInfo = videoTranscoder.inspectVideo(targetOutputFile)
                    finalVideoInfo?.let { info ->
                        if (info.durationUs > 0) finalDurationMs = info.durationUs / 1000L
                        finalResolution = "${minOf(info.width, info.height)}p (${info.width} x ${info.height})"
                    }
                } else {
                    onSpeedProgress(0.1f, "إعادة ترميز الفيديو إلى حد أقصى ${targetQuality.height}p وبسرعة ${targetSpeed}x داخل ${targetFormat.extension.uppercase()}...")
                    finalVideoInfo = videoTranscoder.transcodeVideo(
                        sourceFile = tempDownloadFile,
                        outputFile = targetOutputFile,
                        targetQuality = targetQuality,
                        targetSpeed = targetSpeed,
                        onProgress = onSpeedProgress
                    )
                    if (resumeKey == null) tempDownloadFile.delete()
                    if (finalVideoInfo == null || !targetOutputFile.isFile || targetOutputFile.length() <= 0L) {
                        targetOutputFile.delete()
                        return@withContext null
                    }
                    finalDurationMs = finalVideoInfo!!.durationUs / 1000L
                    finalResolution = "${minOf(finalVideoInfo!!.width, finalVideoInfo!!.height)}p (${finalVideoInfo!!.width} x ${finalVideoInfo!!.height})"
                    sourceBitrateKbps = finalVideoInfo!!.bitRate.takeIf { it > 0 }?.div(1000) ?: sourceBitrateKbps
                }
            } else if (kotlin.math.abs(targetSpeed - 1.0f) > 0.01f || targetFormat == AudioFormat.MP3 || targetFormat == AudioFormat.M4A || targetFormat == AudioFormat.WAV) {
                onSpeedProgress(0.1f, "تطبيق سرعة ${targetSpeed}x وترميز ${targetFormat.name}...")
                val transcodeSuccess = audioTranscoder.transcodeAudio(
                    sourceUri = null,
                    sourceFile = tempDownloadFile,
                    outputFile = targetOutputFile,
                    targetSpeed = targetSpeed,
                    targetFormat = targetFormat,
                    bitrateKbps = targetBitrate.kbps,
                    onProgress = onSpeedProgress,
                    onOutputInfo = { audioOutputInfo = it }
                )
                if (resumeKey == null) tempDownloadFile.delete()

                if (!transcodeSuccess || !targetOutputFile.isFile || targetOutputFile.length() <= 0L) {
                    targetOutputFile.delete()
                    return@withContext null
                }
            } else {
                // A failed move must not be reported as a successful library item.
                val moved = installDownloadedSource(tempDownloadFile, targetOutputFile, keepSource = resumeKey != null)
                if (!moved || !targetOutputFile.isFile || targetOutputFile.length() <= 0L) {
                    if (resumeKey == null) tempDownloadFile.delete()
                    targetOutputFile.delete()
                    return@withContext null
                }
            }

            coroutineJob?.ensureActive()
            if (targetOutputFile != requestedOutputFile &&
                !publishStagedOutput(targetOutputFile, requestedOutputFile)) {
                targetOutputFile.delete()
                return@withContext null
            }
            val finalFileLength = requestedOutputFile.length()
            if (finalFileLength <= 0L) return@withContext null
            val effectiveSpeed = if (isOriginalFormat) 1.0f else targetSpeed
            val effectiveDurationMs = if (isVideoDownload || effectiveSpeed == 1.0f) {
                finalDurationMs
            } else if (effectiveSpeed > 0 && finalDurationMs > 0) {
                (finalDurationMs / effectiveSpeed).toLong()
            } else finalDurationMs
            val outputFileType = when {
                isOriginalFormat -> actualSourceType
                isVideoDownload -> FileType.VIDEO
                requestsAudioContainer -> FileType.AUDIO
                else -> actualSourceType
            }

            return@withContext MediaItem(
                id = UUID.randomUUID().toString(),
                title = requestedOutputFile.nameWithoutExtension,
                filePath = requestedOutputFile.absolutePath,
                durationMs = effectiveDurationMs,
                originalDurationMs = originalDurationMs,
                speed = effectiveSpeed,
                format = if (isOriginalFormat) outputExt.uppercase() else targetFormat.name,
                fileSize = finalFileLength,
                isVideo = outputFileType == FileType.VIDEO,
                fileType = outputFileType,
                resolutionText = finalResolution,
                bitrateKbps = audioOutputInfo?.appliedBitrateKbps
                    ?: if (isVideoDownload) finalVideoInfo?.bitRate?.takeIf { it > 0 }?.div(1000) ?: sourceBitrateKbps ?: 0
                    else if (isOriginalFormat) sourceBitrateKbps ?: 0 else targetBitrate.kbps,
                sourceUrl = item.url
            )
        } catch (cancelled: CancellationException) {
            if (resumeKey == null || !preservePartialOnCancellation) {
                tempDownloadFile.delete()
                checkpointFile?.let { AtomicFile(it).delete() }
            } else if (tempDownloadFile.isFile) {
                runCatching { FileOutputStream(tempDownloadFile, true).use { it.fd.sync() } }
            }
            targetOutputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            e.printStackTrace()
            if (resumeKey == null) tempDownloadFile.delete()
            targetOutputFile.delete()
            return@withContext null
        }
    }

    /**
     * Resolves YouTube direct stream URL from public proxies
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun resolveYouTubeStreamUrl(videoId: String): String {
        val instances = listOf(
            "https://inv.tux.pizza/api/v1/videos/$videoId",
            "https://invidious.nerdvpn.de/api/v1/videos/$videoId",
            "https://pipedapi.kavin.rocks/streams/$videoId"
        )
        val job = currentCoroutineContext()[Job]
        for (url in instances) {
            job?.ensureActive()
            val call = try {
                val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
                httpClient.newCall(request)
            } catch (_: Exception) {
                continue
            }
            val cancellationHandle = job?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true
            ) { cause ->
                if (cause is CancellationException) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "")
                        val formatStreams = json.optJSONArray("formatStreams")
                        if (formatStreams != null && formatStreams.length() > 0) {
                            return formatStreams.getJSONObject(0).optString("url", "")
                        }
                        val audioStreams = json.optJSONArray("audioStreams")
                        if (audioStreams != null && audioStreams.length() > 0) {
                            return audioStreams.getJSONObject(0).optString("url", "")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                job?.ensureActive()
            } finally {
                cancellationHandle?.dispose()
            }
        }
        return ""
    }

    private fun formatSecondsToTime(seconds: Long): String {
        if (seconds <= 0) return "--:--"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }
}
