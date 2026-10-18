package com.example.downloader

import android.content.Context
import android.media.MediaMetadataRetriever
import com.example.audio.AudioTranscoder
import com.example.audio.VideoTranscoder
import com.example.model.AudioBitrate
import com.example.model.AudioFormat
import com.example.model.DuplicateAction
import com.example.model.FileType
import com.example.model.MediaItem
import com.example.model.PlaylistItem
import com.example.model.VideoQuality
import kotlinx.coroutines.Dispatchers
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
                        resolvedStreamUrl = formatStreams.getJSONObject(0).optString("url", "")
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
            directStreamUrl = resolvedStreamUrl
        )
    }

    /**
     * Inspects direct download links or general websites for any file type
     */
    private suspend fun parseDirectOrWebLink(url: String): PlaylistItem {
        var fileName = "ملف_تم_تنزيله"
        var fileType = FileType.OTHER
        var contentLength = 0L
        var isVideo = false

        try {
            // First HEAD request to inspect headers without downloading whole body
            val headReq = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .head()
                .build()

            val resp = try { httpClient.newCall(headReq).execute() } catch (_: Exception) { null }

            val contentType = resp?.header("Content-Type")?.lowercase() ?: ""
            val contentDisposition = resp?.header("Content-Disposition") ?: ""
            contentLength = resp?.header("Content-Length")?.toLongOrNull() ?: 0L

            // Extract file name from Content-Disposition if present
            if (contentDisposition.isNotEmpty()) {
                val nameMatcher = Pattern.compile("filename\\*?=['\"]?(?:UTF-8'')?([^;'\"]+)").matcher(contentDisposition)
                if (nameMatcher.find()) {
                    val raw = nameMatcher.group(1) ?: ""
                    fileName = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
                }
            }

            if (fileName == "ملف_تم_تنزيله") {
                // Infer from URL path
                val pathSegment = url.substringBefore('?').substringAfterLast('/')
                if (pathSegment.isNotEmpty() && pathSegment.contains('.')) {
                    fileName = try { URLDecoder.decode(pathSegment, "UTF-8") } catch (_: Exception) { pathSegment }
                }
            }

            // Determine file type
            val ext = fileName.substringAfterLast('.', "").lowercase()
            when {
                contentType.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "avi", "mov", "3gp") -> {
                    fileType = FileType.VIDEO
                    isVideo = true
                }
                contentType.startsWith("audio/") || ext in listOf("mp3", "m4a", "wav", "aac", "flac", "ogg") -> {
                    fileType = FileType.AUDIO
                    isVideo = false
                }
                contentType == "application/pdf" || ext == "pdf" -> {
                    fileType = FileType.DOCUMENT
                }
                contentType.contains("android.package-archive") || ext == "apk" -> {
                    fileType = FileType.APK
                }
                contentType.contains("zip") || contentType.contains("compressed") || ext in listOf("zip", "rar", "7z", "tar", "gz") -> {
                    fileType = FileType.ARCHIVE
                }
                contentType.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "gif") -> {
                    fileType = FileType.IMAGE
                }
                else -> {
                    fileType = FileType.OTHER
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        val cleanTitle = fileName.substringBeforeLast('.').ifEmpty { "ملف من رابط خارجي" }

        return PlaylistItem(
            id = UUID.randomUUID().toString(),
            title = cleanTitle,
            author = "رابط مباشر / موقع خارجي",
            durationText = if (isVideo || fileType == FileType.AUDIO) "جاري القياس" else "--:--",
            durationSeconds = 0,
            thumbnailUrl = "",
            url = url,
            isSelected = true,
            isVideo = isVideo,
            fileType = fileType,
            fileSizeEstimate = contentLength
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
    suspend fun downloadAndProcessItem(
        item: PlaylistItem,
        targetFormat: AudioFormat,
        targetSpeed: Float,
        targetQuality: VideoQuality = VideoQuality.Q720P,
        targetBitrate: AudioBitrate = AudioBitrate.B128,
        duplicateAction: DuplicateAction = DuplicateAction.DOWNLOAD_ANYWAY,
        outputFileName: String? = null,
        onDownloadProgress: (progress: Float, downloaded: Long, total: Long) -> Unit,
        onSpeedProgress: (progress: Float, stage: String) -> Unit
    ): MediaItem? = withContext(Dispatchers.IO) {
        if (duplicateAction == DuplicateAction.CANCEL) {
            return@withContext null
        }

        val downloadsDir = File(context.filesDir, "audio_downloads").apply { mkdirs() }
        val cleanTitle = item.title.replace(Regex("[^a-zA-Z0-9أ-ي_\\-\\s]"), "").trim().ifEmpty { "ملف_${System.currentTimeMillis()}" }

        // Video containers cannot be produced by the audio-copy path. Refuse an audio
        // source selected for a video extension rather than relabeling its bytes.
        val requestsVideoContainer = targetFormat == AudioFormat.MP4 ||
            targetFormat == AudioFormat.MKV || targetFormat == AudioFormat.WEBM
        if (requestsVideoContainer && !item.isVideo) return@withContext null
        val isVideoDownload = item.isVideo && requestsVideoContainer
        val speedTag = if (kotlin.math.abs(targetSpeed - 1.0f) > 0.01f) "_${targetSpeed}x" else ""
        val qualityTag = if (isVideoDownload) "_${targetQuality.height}p" else ""
        val outputExt = targetFormat.extension
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

        val tempDownloadFile = File(downloadsDir, "temp_download_${UUID.randomUUID()}.tmp")

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

            var downloadSuccessful = false
            var originalDurationMs = item.durationSeconds * 1000L
            var finalDurationMs = originalDurationMs
            var finalResolution = ""
            var sourceBitrateKbps: Int? = null
            var finalVideoInfo: VideoTranscoder.VideoInfo? = null

            // Attempt real streaming download
            if (streamUrl.isNotEmpty() && !isYouTubeVideoUrl(streamUrl)) {
                try {
                    val req = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", userAgent)
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body
                        if (resp.isSuccessful && body != null) {
                            val totalBytes = body.contentLength()
                            var bytesCopied = 0L

                            body.byteStream().use { input ->
                                FileOutputStream(tempDownloadFile).use { output ->
                                    val buffer = ByteArray(16384)
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        output.write(buffer, 0, read)
                                        bytesCopied += read
                                        if (totalBytes > 0) {
                                            onDownloadProgress(bytesCopied.toFloat() / totalBytes, bytesCopied, totalBytes)
                                        }
                                    }
                                }
                            }

                            downloadSuccessful = tempDownloadFile.exists() && tempDownloadFile.length() > 0L
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Never substitute generated content for a failed download.
            if (!downloadSuccessful) {
                tempDownloadFile.delete()
                return@withContext null
            }

            // Inspect downloaded file metadata (real duration, resolution)
            try {
                val retriever = MediaMetadataRetriever()
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
                retriever.release()
            } catch (_: Exception) {}

            if (isVideoDownload) {
                val videoInfo = videoTranscoder.inspectVideo(tempDownloadFile)
                if (videoInfo == null) {
                    onSpeedProgress(0.1f, "تعذر التحقق من مسار الفيديو؛ أُلغي إخراج الملف.")
                    tempDownloadFile.delete()
                    return@withContext null
                }
                finalResolution = "${minOf(videoInfo.width, videoInfo.height)}p (${videoInfo.width} x ${videoInfo.height})"
                if (sourceBitrateKbps == null && videoInfo.bitRate > 0) {
                    sourceBitrateKbps = videoInfo.bitRate / 1000
                }
            }

            // 2. Re-encode video through Android's decoder/encoder for the requested size and speed.
            var audioOutputInfo: AudioTranscoder.AudioOutputInfo? = null
            if (isVideoDownload) {
                if (targetFormat == AudioFormat.WEBM) {
                    val sourceInfo = videoTranscoder.inspectVideo(tempDownloadFile)
                    val requestedShortSide = sourceInfo?.let { minOf(it.width, it.height, targetQuality.height) } ?: 0
                    if (sourceInfo == null || sourceInfo.width <= 0 || sourceInfo.height <= 0 ||
                        kotlin.math.abs(targetSpeed - 1.0f) > 0.01f ||
                        minOf(sourceInfo.width, sourceInfo.height) > targetQuality.height) {
                        onSpeedProgress(0.1f, "إعادة الترميز إلى WebM غير مدعومة؛ يتطلب WebM الحالي نسخ مسار المصدر دون تغيير الدقة أو السرعة.")
                        tempDownloadFile.delete()
                        return@withContext null
                    }
                    onSpeedProgress(0.1f, "إعادة تعبئة مسار المصدر في حاوية WebM دون تغيير الدقة أو السرعة (حد الدقة ${requestedShortSide}px)...")
                    val remuxSuccess = videoTranscoder.remuxVideo(
                        sourceFile = tempDownloadFile,
                        outputFile = targetOutputFile,
                        onProgress = onSpeedProgress
                    )
                    tempDownloadFile.delete()
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
                    tempDownloadFile.delete()
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
                tempDownloadFile.delete()

                if (!transcodeSuccess || !targetOutputFile.isFile || targetOutputFile.length() <= 0L) {
                    targetOutputFile.delete()
                    return@withContext null
                }
            } else {
                // A failed move must not be reported as a successful library item.
                val moved = tempDownloadFile.renameTo(targetOutputFile)
                if (!moved || !targetOutputFile.isFile || targetOutputFile.length() <= 0L) {
                    tempDownloadFile.delete()
                    targetOutputFile.delete()
                    return@withContext null
                }
            }

            if (targetOutputFile != requestedOutputFile &&
                !publishStagedOutput(targetOutputFile, requestedOutputFile)) {
                targetOutputFile.delete()
                return@withContext null
            }
            val finalFileLength = requestedOutputFile.length()
            if (finalFileLength <= 0L) return@withContext null
            val effectiveDurationMs = if (isVideoDownload) finalDurationMs else if (targetSpeed > 0 && finalDurationMs > 0) {
                (finalDurationMs / targetSpeed).toLong()
            } else finalDurationMs

            return@withContext MediaItem(
                id = UUID.randomUUID().toString(),
                title = requestedOutputFile.nameWithoutExtension,
                filePath = requestedOutputFile.absolutePath,
                durationMs = effectiveDurationMs,
                originalDurationMs = originalDurationMs,
                speed = targetSpeed,
                format = targetFormat.name,
                fileSize = finalFileLength,
                isVideo = isVideoDownload,
                fileType = when {
                    isVideoDownload -> FileType.VIDEO
                    targetFormat == AudioFormat.MP3 || targetFormat == AudioFormat.M4A ||
                        targetFormat == AudioFormat.WAV -> FileType.AUDIO
                    else -> item.fileType
                },
                resolutionText = finalResolution,
                bitrateKbps = audioOutputInfo?.appliedBitrateKbps
                    ?: if (isVideoDownload) finalVideoInfo?.bitRate?.takeIf { it > 0 }?.div(1000) ?: sourceBitrateKbps ?: 0 else targetBitrate.kbps,
                sourceUrl = item.url
            )
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempDownloadFile.exists()) tempDownloadFile.delete()
            targetOutputFile.delete()
            return@withContext null
        }
    }

    /**
     * Resolves YouTube direct stream URL from public proxies
     */
    private suspend fun resolveYouTubeStreamUrl(videoId: String): String {
        val instances = listOf(
            "https://inv.tux.pizza/api/v1/videos/$videoId",
            "https://invidious.nerdvpn.de/api/v1/videos/$videoId",
            "https://pipedapi.kavin.rocks/streams/$videoId"
        )
        for (url in instances) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val formatStreams = json.optJSONArray("formatStreams")
                    if (formatStreams != null && formatStreams.length() > 0) {
                        return formatStreams.getJSONObject(0).optString("url", "")
                    }
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null && audioStreams.length() > 0) {
                        return audioStreams.getJSONObject(0).optString("url", "")
                    }
                }
            } catch (_: Exception) {}
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
