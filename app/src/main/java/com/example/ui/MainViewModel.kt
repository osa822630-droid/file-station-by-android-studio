package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayerController
import com.example.audio.AudioTranscoder
import com.example.audio.VideoTranscoder
import com.example.data.MediaItemRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
    val pendingAction: (DuplicateAction) -> Unit
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val transcoder = AudioTranscoder(application)
    private val videoTranscoder = VideoTranscoder(application)
    val downloader = MediaDownloader(application, transcoder)
    val repository = MediaItemRepository(application)
    val player = AudioPlayerController(application)

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

    private val _targetDownloadFormat = MutableStateFlow(AudioFormat.MP4) // Default MP4 video or MP3
    val targetDownloadFormat = _targetDownloadFormat.asStateFlow()

    private val _targetDownloadQuality = MutableStateFlow(VideoQuality.Q720P)
    val targetDownloadQuality = _targetDownloadQuality.asStateFlow()

    private val _targetDownloadSpeed = MutableStateFlow(1.0f) // Speed options: 0.5x, 0.75x, 1x, 1.25x, 1.5x, 1.75x, 2x
    val targetDownloadSpeed = _targetDownloadSpeed.asStateFlow()

    private val _targetDownloadBitrate = MutableStateFlow(AudioBitrate.B128)
    val targetDownloadBitrate = _targetDownloadBitrate.asStateFlow()

    // Conflict Dialog state
    private val _duplicateConflict = MutableStateFlow<DuplicateConflict?>(null)
    val duplicateConflict = _duplicateConflict.asStateFlow()

    // Local file speed changer & converter state
    private val _selectedLocalUri = MutableStateFlow<Uri?>(null)
    val selectedLocalUri = _selectedLocalUri.asStateFlow()

    private val _selectedLocalMetadata = MutableStateFlow<MediaItem?>(null)
    val selectedLocalMetadata = _selectedLocalMetadata.asStateFlow()

    private val _targetLocalSpeed = MutableStateFlow(1.0f)
    val targetLocalSpeed = _targetLocalSpeed.asStateFlow()

    private val _targetLocalFormat = MutableStateFlow(AudioFormat.MP3)
    val targetLocalFormat = _targetLocalFormat.asStateFlow()

    private val _targetLocalBitrate = MutableStateFlow(AudioBitrate.B128)
    val targetLocalBitrate = _targetLocalBitrate.asStateFlow()

    private val _targetLocalQuality = MutableStateFlow(VideoQuality.Q720P)
    val targetLocalQuality = _targetLocalQuality.asStateFlow()

    private val _conversionState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val conversionState = _conversionState.asStateFlow()

    // Web Search state
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()

    fun updateDownloadUrl(url: String) {
        _downloadUrl.value = url
    }

    fun setTargetDownloadFormat(format: AudioFormat) {
        _targetDownloadFormat.value = format
    }

    fun setTargetDownloadQuality(quality: VideoQuality) {
        _targetDownloadQuality.value = quality
    }

    fun setTargetDownloadSpeed(speed: Float) {
        _targetDownloadSpeed.value = speed
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

    fun setTargetLocalBitrate(bitrate: AudioBitrate) {
        _targetLocalBitrate.value = bitrate
    }

    fun setTargetLocalQuality(quality: VideoQuality) {
        _targetLocalQuality.value = quality
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
                if (info.isPlaylist) {
                    _downloadState.value = DownloadState.PlaylistReady(
                        playlistTitle = info.playlistTitle,
                        items = info.items
                    )
                } else if (info.items.isNotEmpty()) {
                    _downloadState.value = DownloadState.PlaylistReady(
                        playlistTitle = if (info.items[0].isVideo) "فيديو جاهز للتحميل" else "ملف جاهز للتحميل",
                        items = info.items
                    )
                } else {
                    _downloadState.value = DownloadState.Error("تعذر استخراج معلومات الرابط. يرجى التأكد من الرابط والمحاولة مجدداً.")
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
     * Downloads selected items with duplicate check prompt:
     * (تحميل على أي حال / تحميل واستبدال القديم / إلغاء)
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
        val quality = _targetDownloadQuality.value
        val speed = _targetDownloadSpeed.value
        val bitrate = _targetDownloadBitrate.value

        // Check if the first item already exists
        val firstItem = itemsToDownload.first()
        val cleanTitle = firstItem.title.replace(Regex("[^a-zA-Z0-9أ-ي_\\-\\s]"), "").trim().ifEmpty { "ملف" }
        val speedTag = if (speed != 1.0f) "_${speed}x" else ""
        val targetName = "$cleanTitle$speedTag.${format.extension}"

        val existingFile = downloader.checkFileExists(targetName)
        if (existingFile != null) {
            // Prompt user with the 3 choices: (تحميل على اي حال / تحميل واستبدال القديم / الغاء)
            _duplicateConflict.value = DuplicateConflict(
                fileName = existingFile.name,
                pendingAction = { choice ->
                    if (choice != DuplicateAction.CANCEL) {
                        executeDownload(itemsToDownload, format, quality, speed, bitrate, choice)
                    } else {
                        _actionMessage.value = "تم إلغاء التحميل"
                    }
                }
            )
        } else {
            executeDownload(itemsToDownload, format, quality, speed, bitrate, DuplicateAction.DOWNLOAD_ANYWAY)
        }
    }

    private fun executeDownload(
        itemsToDownload: List<PlaylistItem>,
        format: AudioFormat,
        quality: VideoQuality,
        speed: Float,
        bitrate: AudioBitrate,
        duplicateAction: DuplicateAction
    ) {
        viewModelScope.launch {
            val downloadedList = mutableListOf<MediaItem>()
            val total = itemsToDownload.size

            for ((index, item) in itemsToDownload.withIndex()) {
                _downloadState.value = DownloadState.Downloading(
                    currentItemTitle = item.title,
                    currentIndex = index + 1,
                    totalCount = total,
                    progress = 0.05f,
                    bytesDownloaded = 0,
                    totalBytes = 0
                )

                val result = downloader.downloadAndProcessItem(
                    item = item,
                    targetFormat = format,
                    targetQuality = quality,
                    targetSpeed = speed,
                    targetBitrate = bitrate,
                    duplicateAction = duplicateAction,
                    onDownloadProgress = { progress, downloaded, totalBytes ->
                        _downloadState.value = DownloadState.Downloading(
                            currentItemTitle = item.title,
                            currentIndex = index + 1,
                            totalCount = total,
                            progress = progress * 0.7f,
                            bytesDownloaded = downloaded,
                            totalBytes = totalBytes
                        )
                    },
                    onSpeedProgress = { speedProgress, stage ->
                        _downloadState.value = DownloadState.ProcessingSpeed(
                            itemTitle = item.title,
                            progress = 0.7f + speedProgress * 0.3f,
                            speed = speed
                        )
                    }
                )

                if (result != null) {
                    repository.addItem(result)
                    downloadedList.add(result)
                }
            }

            _downloadState.value = DownloadState.Completed(downloadedList)
            _actionMessage.value = "تم تحميل ${downloadedList.size} ملف بنجاح في File Station!"
        }
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Local File selection: inspect metadata
     */
    fun onLocalFileSelected(uri: Uri) {
        _selectedLocalUri.value = uri
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
        val format = _targetLocalFormat.value
        val bitrate = _targetLocalBitrate.value
        val quality = _targetLocalQuality.value

        viewModelScope.launch {
            val isVideoOutput = format == AudioFormat.MP4 || format == AudioFormat.MKV || format == AudioFormat.WEBM
            val isVideoSource = meta?.isVideo == true

            _conversionState.value = ProcessingState.Processing(0.05f, "بدء تحويل الملف...")

            val outputDir = File(getApplication<Application>().filesDir, "converted_audio").apply { mkdirs() }
            val cleanTitle = (meta?.title ?: "ملف_${System.currentTimeMillis()}")
                .replace(Regex("[^a-zA-Z0-9أ-ي_\\-\\s]"), "")
                .trim()
                .ifEmpty { "ملف" }
            val speedLabel = if (speed != 1.0f) "_${speed}x" else ""
            val qualityLabel = if (isVideoOutput) "_${quality.height}p" else "_${bitrate.label}"
            val outputFile = File(outputDir, "${cleanTitle}${speedLabel}${qualityLabel}.${format.extension}")

            // If source is video and user wants to downscale video quality (e.g. 1080p -> 720p, 480p, 360p)
            if (isVideoSource && isVideoOutput) {
                // Copy stream to temp file to transcode
                val tempSourceFile = File(outputDir, "temp_src_${UUID.randomUUID()}.mp4")
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        tempSourceFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {}

                val success = videoTranscoder.downscaleVideo(
                    sourceFile = tempSourceFile,
                    outputFile = outputFile,
                    targetQuality = quality,
                    onProgress = { progress, stage ->
                        _conversionState.value = ProcessingState.Processing(progress, stage)
                    }
                )
                tempSourceFile.delete()

                if (success && outputFile.exists()) {
                    val newItem = MediaItem(
                        id = UUID.randomUUID().toString(),
                        title = "${cleanTitle} (${quality.label})",
                        filePath = outputFile.absolutePath,
                        durationMs = meta?.durationMs ?: 0L,
                        originalDurationMs = meta?.durationMs ?: 0L,
                        speed = 1.0f,
                        format = format.name,
                        fileSize = outputFile.length(),
                        isVideo = true,
                        fileType = FileType.VIDEO,
                        resolutionText = quality.label,
                        sourceUrl = uri.toString()
                    )
                    repository.addItem(newItem)
                    _conversionState.value = ProcessingState.Success(
                        newItem,
                        "تم تقليل جودة وتحويل الفيديو بنجاح إلى ${quality.label}!"
                    )
                    _actionMessage.value = "تم تحويل الفيديو وحفظه بنجاح!"
                } else {
                    _conversionState.value = ProcessingState.Error("فشلت عملية معالجة الفيديو.")
                }
            } else {
                // Audio conversion or extracting audio from video with WSOLA speed & bitrate
                val success = transcoder.transcodeAudio(
                    sourceUri = uri,
                    sourceFile = null,
                    outputFile = outputFile,
                    targetSpeed = speed,
                    targetFormat = format,
                    bitrateKbps = bitrate.kbps,
                    onProgress = { progress, stage ->
                        _conversionState.value = ProcessingState.Processing(progress, stage)
                    }
                )

                if (success && outputFile.exists()) {
                    val finalDuration = if (meta != null && meta.durationMs > 0 && speed > 0) {
                        (meta.durationMs / speed).toLong()
                    } else 0L

                    val newItem = MediaItem(
                        id = UUID.randomUUID().toString(),
                        title = "${cleanTitle} (${speed}x, ${bitrate.label})",
                        filePath = outputFile.absolutePath,
                        durationMs = finalDuration,
                        originalDurationMs = meta?.durationMs ?: 0L,
                        speed = speed,
                        format = format.name,
                        fileSize = outputFile.length(),
                        isVideo = false,
                        fileType = FileType.AUDIO,
                        bitrateKbps = bitrate.kbps,
                        sourceUrl = uri.toString()
                    )
                    repository.addItem(newItem)
                    _conversionState.value = ProcessingState.Success(
                        newItem,
                        "تم استخراج وتحويل الصوت بنجاح بصيغة ${format.name} ومعدل ${bitrate.label}!"
                    )
                    _actionMessage.value = "تم تحويل الملف بنجاح! جاهز للاستماع والمشاركة."
                } else {
                    _conversionState.value = ProcessingState.Error("فشلت عملية التحويل. يرجى تجربة ملف آخر.")
                }
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
            repository.deleteItem(item)
            _actionMessage.value = "تم حذف الملف"
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
            if (success) {
                _actionMessage.value = "تم تصدير الملف إلى مجلد التنزيلات (Downloads) بالجهاز"
            } else {
                _actionMessage.value = "فشل تصدير الملف"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
