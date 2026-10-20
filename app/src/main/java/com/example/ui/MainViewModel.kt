package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayerController
import com.example.audio.AudioTranscoder
import com.example.audio.VideoTranscoder
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
        viewModelScope.launch {
            val downloadedList = mutableListOf<MediaItem>()
            var failedCount = 0
            val total = requests.size

            for ((index, request) in requests.withIndex()) {
                val item = request.item
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
                    targetSpeed = speed,
                    targetQuality = quality,
                    targetBitrate = bitrate,
                    duplicateAction = request.duplicateAction,
                    outputFileName = request.filePlan.outputFileNameFor(request.duplicateAction),
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
                } else {
                    failedCount++
                }
            }

            if (downloadedList.isEmpty()) {
                _downloadState.value = DownloadState.Error("فشل تحميل جميع العناصر المحددة. لم يتم حفظ ملفات.")
                _actionMessage.value = "فشل التحميل؛ لم يُحفظ أي ملف. تحقق من الرابط والاتصال ثم أعد المحاولة."
            } else {
                _downloadState.value = DownloadState.Completed(downloadedList, failedCount)
                _actionMessage.value = if (failedCount == 0) {
                    "تم تحميل ${downloadedList.size} ملف بنجاح في File Station."
                } else {
                    "تم تحميل ${downloadedList.size} ملف، وفشل تحميل $failedCount عنصر."
                }
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
        val quality = _targetLocalQuality.value
        val format = _targetLocalFormat.value
        val bitrate = _targetLocalBitrate.value

        viewModelScope.launch {
            val isVideoOutput = format == AudioFormat.MP4 || format == AudioFormat.MKV || format == AudioFormat.WEBM
            val isVideoSource = meta?.isVideo == true
            if (isVideoOutput && !isVideoSource) {
                _conversionState.value = ProcessingState.Error("صيغة الفيديو تتطلب مصدرًا يحتوي على مسار فيديو.")
                return@launch
            }
            _conversionState.value = ProcessingState.Processing(0.05f, "بدء تحويل الملف...")

            val outputDir = File(getApplication<Application>().filesDir, "converted_audio").apply { mkdirs() }
            val cleanTitle = (meta?.title ?: "ملف_${System.currentTimeMillis()}")
                .replace(Regex("[^a-zA-Z0-9أ-ي_\\-\\s]"), "")
                .trim()
                .ifEmpty { "ملف" }
            val videoLabel = if (isVideoOutput) "_video_${speed}x" else ""
            val speedLabel = if (!isVideoOutput && speed != 1.0f) "_${speed}x" else ""
            val qualityLabel = if (!isVideoOutput) "_${bitrate.label}" else ""
            val uniqueSuffix = UUID.randomUUID().toString().take(8)
            val outputFile = File(outputDir, "${cleanTitle}${videoLabel}${speedLabel}${qualityLabel}_$uniqueSuffix.${format.extension}")

            // Copy the selected content URI to a private temporary file, then use Media3 to
            // decode, scale and re-encode the video at the requested speed.
            if (isVideoSource && isVideoOutput) {
                val tempSourceFile = File(outputDir, "temp_src_${UUID.randomUUID()}")
                val copied = try {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("تعذر فتح الملف المحدد")
                    input.use { source -> tempSourceFile.outputStream().use { output -> source.copyTo(output) } }
                    tempSourceFile.isFile && tempSourceFile.length() > 0L
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Unable to stage selected video", e)
                    false
                }
                if (!copied) {
                    tempSourceFile.delete()
                    _conversionState.value = ProcessingState.Error("تعذر قراءة ملف الفيديو المحدد؛ لم يُنشأ ملف ناتج.")
                    return@launch
                }

                val outputInfo = try {
                    videoTranscoder.transcodeVideo(
                        sourceFile = tempSourceFile,
                        outputFile = outputFile,
                        targetQuality = quality,
                        targetSpeed = speed,
                        onProgress = { progress, stage ->
                            _conversionState.value = ProcessingState.Processing(progress, stage)
                        }
                    )
                } finally {
                    tempSourceFile.delete()
                }

                if (outputInfo != null && outputFile.isFile && outputFile.length() > 0L) {
                    val actualResolution = "${outputInfo.width} × ${outputInfo.height}"
                    val outputDurationMs = outputInfo.durationUs / 1000L
                    val newItem = MediaItem(
                        id = UUID.randomUUID().toString(),
                        title = "$cleanTitle ($actualResolution, ${speed}x)",
                        filePath = outputFile.absolutePath,
                        durationMs = outputDurationMs,
                        originalDurationMs = meta?.durationMs ?: 0L,
                        speed = speed,
                        format = format.name,
                        fileSize = outputFile.length(),
                        isVideo = true,
                        fileType = FileType.VIDEO,
                        resolutionText = "${minOf(outputInfo.width, outputInfo.height)}p ($actualResolution)",
                        bitrateKbps = outputInfo.bitRate.takeIf { it > 0 }?.div(1000) ?: 0,
                        sourceUrl = uri.toString()
                    )
                    repository.addItem(newItem)
                    _conversionState.value = ProcessingState.Success(
                        newItem,
                        "أُعيد ترميز الفيديو فعليًا إلى ${actualResolution} بسرعة ${speed}x داخل ${format.extension.uppercase()}."
                    )
                    _actionMessage.value = "اكتمل تغيير الدقة/السرعة وإعادة الترميز؛ الدقة الفعلية ${actualResolution}."
                } else {
                    outputFile.delete()
                    _conversionState.value = ProcessingState.Error("فشلت إعادة ترميز الفيديو. تحقق من دعم الجهاز للترميز ثم أعد المحاولة؛ لم يُحفظ ملف جزئي.")
                }
            } else {
                // Audio conversion or extracting audio from video with WSOLA speed & bitrate
                var audioOutputInfo: AudioTranscoder.AudioOutputInfo? = null
                val success = transcoder.transcodeAudio(
                    sourceUri = uri,
                    sourceFile = null,
                    outputFile = outputFile,
                    targetSpeed = speed,
                    targetFormat = format,
                    bitrateKbps = bitrate.kbps,
                    onProgress = { progress, stage ->
                        _conversionState.value = ProcessingState.Processing(progress, stage)
                    },
                    onOutputInfo = { audioOutputInfo = it }
                )

                if (success && outputFile.exists()) {
                    val effectiveBitrateKbps = audioOutputInfo?.appliedBitrateKbps ?: bitrate.kbps
                    val bitrateText = if (format == AudioFormat.WAV) {
                        "$effectiveBitrateKbps kbps PCM"
                    } else {
                        "$effectiveBitrateKbps kbps"
                    }
                    val adjustmentDetails = audioOutputInfo?.let { info ->
                        buildList {
                            if (info.requestWasAdjusted) {
                                add("المطلوب ${info.requestedBitrateKbps} kbps، والمطبق ${info.appliedBitrateKbps} kbps")
                            }
                            if (info.sampleRate != info.sourceSampleRate || info.channels != info.sourceChannels) {
                                add("تهيأت العينة إلى ${info.sampleRate} Hz / ${info.channels} قناة")
                            }
                        }
                    }.orEmpty()
                    val adjustmentText = if (adjustmentDetails.isEmpty()) "" else " (${adjustmentDetails.joinToString("؛ ")})"
                    val finalDuration = if (meta != null && meta.durationMs > 0 && speed > 0) {
                        (meta.durationMs / speed).toLong()
                    } else 0L

                    val newItem = MediaItem(
                        id = UUID.randomUUID().toString(),
                        title = "${cleanTitle} (${speed}x, $bitrateText)",
                        filePath = outputFile.absolutePath,
                        durationMs = finalDuration,
                        originalDurationMs = meta?.durationMs ?: 0L,
                        speed = speed,
                        format = format.name,
                        fileSize = outputFile.length(),
                        isVideo = false,
                        fileType = FileType.AUDIO,
                        bitrateKbps = effectiveBitrateKbps,
                        sourceUrl = uri.toString()
                    )
                    repository.addItem(newItem)
                    _conversionState.value = ProcessingState.Success(
                        newItem,
                        "تم استخراج وتحويل الصوت بنجاح بصيغة ${format.name} بمعدل $bitrateText$adjustmentText!"
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
