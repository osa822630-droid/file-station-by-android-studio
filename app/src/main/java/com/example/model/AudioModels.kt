package com.example.model

enum class FileType(val label: String) {
    VIDEO("فيديو"),
    AUDIO("صوت"),
    DOCUMENT("مستند"),
    ARCHIVE("أرشيف مضغوط"),
    APK("تطبيق أندرويد"),
    IMAGE("صورة"),
    OTHER("ملف عام")
}

enum class AudioFormat(val extension: String, val mimeType: String, val label: String) {
    MP3("mp3", "audio/mpeg", "MP3 - متوافق مع كافة المشغلات"),
    M4A("m4a", "audio/mp4", "M4A (AAC) - حجم صغير وجودة عالية"),
    WAV("wav", "audio/wav", "WAV - صوت خام عالي النقاء"),
    MP4("mp4", "video/mp4", "MP4 - فيديو كامل عالي الجودة"),
    MKV("mkv", "video/x-matroska", "MKV - فيديو متعدد المسارات"),
    WEBM("webm", "video/webm", "WebM - فيديو مضغوط للويب")
}

enum class VideoQuality(val label: String, val height: Int, val width: Int, val estimatedBitrateKbps: Int) {
    Q1080P("1080p (Full HD)", 1080, 1920, 4500),
    Q720P("720p (HD)", 720, 1280, 2200),
    Q480P("480p (SD)", 480, 854, 1100),
    Q360P("360p (عادي)", 360, 640, 650),
    Q240P("240p (توفير مساحة)", 240, 426, 350)
}

enum class AudioBitrate(val kbps: Int, val label: String, val description: String) {
    B48(48, "48k", "حجم فائق الصغر - ممتاز للمحاضرات وبطاقات الذاكرة"),
    B64(64, "64k", "حجم خفيف جداً - للمقاطع الصوتية وتوفير المساحة"),
    B128(128, "128k", "جودة قياسية متوازنة - خيار موصى به"),
    B192(192, "192k", "جودة جيدة جداً - نقاء صوتي ممتاز"),
    B256(256, "256k", "جودة عالية - مناسب للموسيقى"),
    B320(320, "320k", "أقصى جودة ونقاء - بدون ضغط مفرط")
}

enum class DuplicateAction {
    DOWNLOAD_ANYWAY,    // تحميل على أي حال (حفظ بنسخة جديدة رقمية)
    REPLACE_EXISTING,   // تحميل واستبدال القديم
    CANCEL              // إلغاء
}

data class PlaylistItem(
    val id: String,
    val title: String,
    val author: String = "",
    val durationText: String = "",
    val durationSeconds: Long = 0,
    val thumbnailUrl: String = "",
    val url: String = "",
    var isSelected: Boolean = true,
    val isVideo: Boolean = false,
    val fileType: FileType = FileType.AUDIO,
    val fileSizeEstimate: Long = 0L,
    val selectedQuality: VideoQuality = VideoQuality.Q720P,
    val directStreamUrl: String = ""
)

data class MediaItem(
    val id: String,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val originalDurationMs: Long = durationMs,
    val speed: Float = 1.0f,
    val format: String,
    val fileSize: Long,
    val isVideo: Boolean = false,
    val fileType: FileType = if (isVideo) FileType.VIDEO else FileType.AUDIO,
    val resolutionText: String = "",
    val bitrateKbps: Int = 128,
    val dateAdded: Long = System.currentTimeMillis(),
    val sourceUrl: String = ""
)

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Processing(val progress: Float, val stageDescription: String) : ProcessingState()
    data class Success(val item: MediaItem, val message: String) : ProcessingState()
    data class Error(val error: String) : ProcessingState()
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class FetchingMetadata(val url: String) : DownloadState()
    data class PlaylistReady(
        val playlistTitle: String,
        val items: List<PlaylistItem>
    ) : DownloadState()
    data class Downloading(
        val currentItemTitle: String,
        val currentIndex: Int,
        val totalCount: Int,
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState()
    data class ProcessingSpeed(
        val itemTitle: String,
        val progress: Float,
        val speed: Float
    ) : DownloadState()
    data class Completed(val downloadedItems: List<MediaItem>) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
