package com.example.downloader

import com.example.model.FileType
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/** Metadata inferred from an inspected URL or from the actual GET response. */
data class DirectDownloadMetadata(
    val fileName: String,
    val mimeType: String,
    val extension: String,
    val fileType: FileType
)

/**
 * Resolves a direct-link file's real identity without trusting a URL suffix over HTTP headers.
 * Successful HTTP status alone is insufficient: common HTML/JSON error pages are rejected before
 * their bytes can be committed as media or as an original-format download.
 */
object DirectDownloadMetadataResolver {
    private val mimeExtensions = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/flac" to "flac",
        "audio/ogg" to "ogg",
        "audio/webm" to "webm",
        "application/ogg" to "ogg",
        "video/mp4" to "mp4",
        "video/x-matroska" to "mkv",
        "video/webm" to "webm",
        "video/quicktime" to "mov",
        "video/x-msvideo" to "avi",
        "video/3gpp" to "3gp",
        "application/pdf" to "pdf",
        "application/vnd.android.package-archive" to "apk",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/avif" to "avif",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/svg+xml" to "svg",
        "image/tiff" to "tif",
        "image/x-icon" to "ico",
        "image/vnd.microsoft.icon" to "ico",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/x-7z-compressed" to "7z",
        "application/vnd.rar" to "rar",
        "application/x-rar-compressed" to "rar",
        "application/gzip" to "gz",
        "application/x-gzip" to "gz",
        "application/x-tar" to "tar",
        "application/json" to "json",
        "text/json" to "json",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/epub+zip" to "epub",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "application/rtf" to "rtf"
    )

    private val extensionTypes = mapOf(
        "mp4" to FileType.VIDEO, "mkv" to FileType.VIDEO, "webm" to FileType.VIDEO,
        "avi" to FileType.VIDEO, "mov" to FileType.VIDEO, "3gp" to FileType.VIDEO,
        "mp3" to FileType.AUDIO, "m4a" to FileType.AUDIO, "wav" to FileType.AUDIO,
        "aac" to FileType.AUDIO, "flac" to FileType.AUDIO, "ogg" to FileType.AUDIO,
        "pdf" to FileType.DOCUMENT, "doc" to FileType.DOCUMENT, "docx" to FileType.DOCUMENT,
        "xls" to FileType.DOCUMENT, "xlsx" to FileType.DOCUMENT, "ppt" to FileType.DOCUMENT,
        "pptx" to FileType.DOCUMENT, "txt" to FileType.DOCUMENT, "csv" to FileType.DOCUMENT,
        "rtf" to FileType.DOCUMENT, "epub" to FileType.DOCUMENT, "json" to FileType.DOCUMENT,
        "apk" to FileType.APK,
        "zip" to FileType.ARCHIVE, "rar" to FileType.ARCHIVE, "7z" to FileType.ARCHIVE,
        "tar" to FileType.ARCHIVE, "gz" to FileType.ARCHIVE, "bz2" to FileType.ARCHIVE,
        "xz" to FileType.ARCHIVE,
        "jpg" to FileType.IMAGE, "jpeg" to FileType.IMAGE, "png" to FileType.IMAGE,
        "webp" to FileType.IMAGE, "gif" to FileType.IMAGE, "bmp" to FileType.IMAGE,
        "avif" to FileType.IMAGE, "heic" to FileType.IMAGE, "heif" to FileType.IMAGE,
        "svg" to FileType.IMAGE, "tif" to FileType.IMAGE, "tiff" to FileType.IMAGE,
        "ico" to FileType.IMAGE
    )

    /** Metadata usable during the initial, usually HEAD-only, inspection. */
    fun fromHeaders(
        contentTypeHeader: String?,
        contentDisposition: String?,
        url: String,
        fallbackFileName: String = ""
    ): DirectDownloadMetadata {
        val mime = normalizeMimeType(contentTypeHeader)
        val fileName = contentDispositionFileName(contentDisposition)
            ?: safeName(fallbackFileName).takeIf { it.isNotBlank() }
            ?: safeName(urlPathName(url))
        val extension = extensionFrom(mime, fileName, url)
        return DirectDownloadMetadata(
            fileName = fileName.ifBlank { "download.$extension" },
            mimeType = mime,
            extension = extension,
            fileType = fileTypeFrom(mime, extension)
        )
    }

    /** Validates actual GET headers and leading bytes before a response is written to disk. */
    fun validateResponse(
        statusCode: Int,
        contentTypeHeader: String?,
        contentDisposition: String?,
        responseUrl: String,
        fallbackFileName: String,
        prefix: ByteArray
    ): DirectDownloadMetadata? {
        if (statusCode !in 200..299 || prefix.isEmpty()) return null
        val mime = normalizeMimeType(contentTypeHeader)
        val metadata = fromHeaders(contentTypeHeader, contentDisposition, responseUrl, fallbackFileName)
        val dispositionType = contentDisposition.orEmpty().substringBefore(';').trim()
        val attachedFileName = contentDispositionFileName(contentDisposition)
        val hasJsonAttachment = dispositionType.equals("attachment", ignoreCase = true) &&
            extensionOf(attachedFileName.orEmpty())?.equals("json", ignoreCase = true) == true
        if (isHtmlMime(mime) || isHtmlBody(prefix) ||
            ((isJsonMime(mime) || isJsonBody(prefix)) && !hasJsonAttachment)) return null
        if (!hasValidKnownSignature(metadata, prefix)) return null
        return metadata
    }

    /** Uses a known MIME mapping first, then a safe filename/URL suffix, finally `.bin`. */
    fun extensionFrom(contentTypeHeader: String?, fileName: String, url: String): String {
        val mime = normalizeMimeType(contentTypeHeader)
        val mapped = mimeExtensions[mime]
        if (mapped != null) return mapped
        val nameExtension = extensionOf(fileName)
        if (nameExtension != null) return nameExtension
        val urlExtension = extensionOf(urlPathName(url))
        return urlExtension ?: "bin"
    }

    fun normalizeMimeType(contentTypeHeader: String?): String =
        contentTypeHeader.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)

    private fun fileTypeFrom(mime: String, extension: String): FileType {
        if (isHtmlMime(mime)) return FileType.OTHER
        if (isJsonMime(mime)) return if (extension.equals("json", ignoreCase = true)) FileType.DOCUMENT else FileType.OTHER
        if (mime.startsWith("audio/")) return FileType.AUDIO
        if (mime.startsWith("video/")) return FileType.VIDEO
        if (mime.startsWith("image/")) return FileType.IMAGE
        return when (mime) {
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain", "text/csv", "application/rtf" -> FileType.DOCUMENT
            "application/vnd.android.package-archive" -> FileType.APK
            "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
            "application/vnd.rar", "application/x-rar-compressed", "application/gzip",
            "application/x-gzip", "application/x-tar" -> FileType.ARCHIVE
            else -> extensionTypes[extension.lowercase(Locale.ROOT)] ?: FileType.OTHER
        }
    }

    private fun isHtmlMime(mime: String): Boolean =
        mime == "text/html" || mime == "application/xhtml+xml"

    private fun isJsonMime(mime: String): Boolean =
        mime == "application/json" || mime == "text/json" || mime.endsWith("+json")

    private fun textPrefix(prefix: ByteArray): String = prefix.toString(StandardCharsets.UTF_8)
        .removePrefix("\uFEFF")
        .trimStart(' ', '\t', '\r', '\n', '\u0000')
        .lowercase(Locale.ROOT)

    private fun isHtmlBody(prefix: ByteArray): Boolean {
        val text = textPrefix(prefix)
        return text.startsWith("<!doctype html") || text.startsWith("<html") ||
            text.startsWith("<head") || text.startsWith("<body")
    }

    private fun isJsonBody(prefix: ByteArray): Boolean {
        val text = textPrefix(prefix)
        return text.startsWith("{") || text.startsWith("[")
    }

    private fun hasValidKnownSignature(metadata: DirectDownloadMetadata, prefix: ByteArray): Boolean {
        val bytes = prefix
        fun starts(vararg values: Int): Boolean =
            bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 0xff) == values[it] }
        return when (metadata.extension.lowercase(Locale.ROOT)) {
            "pdf" -> bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(StandardCharsets.US_ASCII))
            "png" -> starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            "jpg", "jpeg" -> starts(0xff, 0xd8, 0xff)
            "gif" -> bytes.size >= 6 && String(bytes, 0, 6, StandardCharsets.US_ASCII) in setOf("GIF87a", "GIF89a")
            "bmp" -> starts(0x42, 0x4d)
            "ico" -> starts(0x00, 0x00, 0x01, 0x00)
            "tif", "tiff" -> starts(0x49, 0x49, 0x2a, 0x00) || starts(0x4d, 0x4d, 0x00, 0x2a)
            "svg" -> {
                val text = textPrefix(prefix)
                text.startsWith("<svg") || (text.startsWith("<?xml") && text.contains("<svg"))
            }
            "avif", "heic", "heif" -> bytes.size >= 12 &&
                String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp" &&
                String(bytes, 8, 4, StandardCharsets.US_ASCII) in setOf(
                    "avif", "avis", "heic", "heix", "hevc", "hevx", "mif1", "msf1"
                )
            "webp" -> bytes.size >= 12 &&
                String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
            "zip", "apk", "docx", "xlsx", "pptx", "epub" -> starts(0x50, 0x4b, 0x03, 0x04) || starts(0x50, 0x4b, 0x05, 0x06)
            "7z" -> starts(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)
            "rar" -> bytes.size >= 7 && String(bytes, 0, 7, StandardCharsets.US_ASCII) == "Rar!\u001a\u0007"
            "gz" -> starts(0x1f, 0x8b)
            "wav" -> bytes.size >= 12 &&
                String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WAVE"
            "flac" -> starts(0x66, 0x4c, 0x61, 0x43)
            "ogg" -> bytes.size >= 4 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "OggS"
            "mp3" -> starts(0x49, 0x44, 0x33) ||
                (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0)
            "mp4", "m4a", "mov", "3gp" -> bytes.size >= 8 &&
                String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp"
            "mkv", "webm" -> starts(0x1a, 0x45, 0xdf, 0xa3)
            "avi" -> bytes.size >= 12 &&
                String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, StandardCharsets.US_ASCII) == "AVI "
            else -> true
        }
    }

    private fun contentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val extended = Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')?([^;]+)", Pattern.CASE_INSENSITIVE)
            .matcher(header)
        val regular = Pattern.compile("filename\\s*=\\s*\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE)
            .matcher(header)
        val raw = when {
            extended.find() -> extended.group(1)
            regular.find() -> regular.group(1)
            else -> null
        }?.trim()?.trim('"', '\'') ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return safeName(decoded).takeIf { it.isNotBlank() }
    }

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot { it.isISOControl() }
        .trim()

    private fun urlPathName(url: String): String =
        url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .let(::safeName)

    private fun extensionOf(name: String): String? {
        val clean = safeName(name)
        val dot = clean.lastIndexOf('.')
        if (dot <= 0 || dot == clean.lastIndex) return null
        return clean.substring(dot + 1).lowercase(Locale.ROOT)
            .takeIf { it.length in 1..12 && it.all { char -> char.isLetterOrDigit() } }
    }
}
