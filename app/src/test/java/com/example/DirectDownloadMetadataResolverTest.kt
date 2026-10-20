package com.example

import com.example.downloader.DirectDownloadMetadataResolver
import com.example.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

class DirectDownloadMetadataResolverTest {
    @Test
    fun htmlSuccessPageIsRejectedEvenWhenUrlLooksLikeVideo() {
        val result = DirectDownloadMetadataResolver.validateResponse(
            statusCode = 200,
            contentTypeHeader = "text/html; charset=utf-8",
            contentDisposition = null,
            responseUrl = "https://example.test/clip.mp4",
            fallbackFileName = "clip.mp4",
            prefix = "<!doctype html><html>not found</html>".toByteArray()
        )

        assertNull(result)
    }

    @Test
    fun jsonErrorBodyIsRejectedEvenWithGenericMimeAndMediaExtension() {
        val result = DirectDownloadMetadataResolver.validateResponse(
            statusCode = 200,
            contentTypeHeader = "application/octet-stream",
            contentDisposition = null,
            responseUrl = "https://example.test/clip.mp4",
            fallbackFileName = "clip.mp4",
            prefix = "{\"error\":\"not found\"}".toByteArray()
        )

        assertNull(result)
    }

    @Test
    fun explicitlyAttachedJsonDocumentIsPreservedButUnlabelledJsonIsRejected() {
        val result = DirectDownloadMetadataResolver.validateResponse(
            statusCode = 200,
            contentTypeHeader = "application/json",
            contentDisposition = "attachment; filename=settings.json",
            responseUrl = "https://example.test/download",
            fallbackFileName = "",
            prefix = "{\"theme\":\"dark\"}".toByteArray()
        )

        val attachedMetadata = result ?: throw AssertionError("Explicit JSON attachment should be accepted")
        assertEquals("json", attachedMetadata.extension)
        assertEquals(FileType.DOCUMENT, attachedMetadata.fileType)

        val unlabelledJson = DirectDownloadMetadataResolver.validateResponse(
            statusCode = 200,
            contentTypeHeader = "application/json",
            contentDisposition = null,
            responseUrl = "https://example.test/api/error",
            fallbackFileName = "",
            prefix = "{\"error\":\"not found\"}".toByteArray()
        )
        assertNull("JSON without an explicit attachment filename is treated as an API/error response", unlabelledJson)
    }

    @Test
    fun contentTypeOverridesConflictingFilenameAndProvidesActualExtension() {
        val pdfPrefix = "%PDF-1.7\n".toByteArray(StandardCharsets.US_ASCII)
        val result = DirectDownloadMetadataResolver.validateResponse(
            statusCode = 200,
            contentTypeHeader = "application/pdf; charset=binary",
            contentDisposition = "attachment; filename=\"manual.mp4\"",
            responseUrl = "https://example.test/manual.mp4",
            fallbackFileName = "manual.mp4",
            prefix = pdfPrefix
        )

        val actual = result ?: throw AssertionError("MIME-identified PDF should be accepted")
        assertEquals("manual.mp4", actual.fileName)
        assertEquals("pdf", actual.extension)
        assertEquals(FileType.DOCUMENT, actual.fileType)
    }

    @Test
    fun originalPublicFormatsAreRecognizedByMimeAndSignature() {
        val cases = listOf(
            Triple("application/pdf", "manual.pdf", "%PDF-1.7\n".toByteArray()),
            Triple("application/vnd.android.package-archive", "app.apk", byteArrayOf(0x50, 0x4b, 0x03, 0x04)),
            Triple("image/png", "image.png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)),
            Triple("application/zip", "bundle.zip", byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        )
        val expectedTypes = listOf(FileType.DOCUMENT, FileType.APK, FileType.IMAGE, FileType.ARCHIVE)

        cases.forEachIndexed { index, (mime, fileName, prefix) ->
            val result = DirectDownloadMetadataResolver.validateResponse(
                statusCode = 200,
                contentTypeHeader = mime,
                contentDisposition = "attachment; filename=\"$fileName\"",
                responseUrl = "https://example.test/download",
                fallbackFileName = "",
                prefix = prefix
            )
            val actual = result ?: throw AssertionError("$fileName should be accepted as a valid original file")
            assertEquals(expectedTypes[index], actual.fileType)
            assertEquals(fileName.substringAfterLast('.'), actual.extension)
        }
    }

    @Test
    fun rejectsEmptyAndNonSuccessfulResponses() {
        assertNull(
            DirectDownloadMetadataResolver.validateResponse(
                statusCode = 200,
                contentTypeHeader = "application/pdf",
                contentDisposition = null,
                responseUrl = "https://example.test/a.pdf",
                fallbackFileName = "a.pdf",
                prefix = byteArrayOf()
            )
        )
        assertNull(
            DirectDownloadMetadataResolver.validateResponse(
                statusCode = 503,
                contentTypeHeader = "application/pdf",
                contentDisposition = null,
                responseUrl = "https://example.test/a.pdf",
                fallbackFileName = "a.pdf",
                prefix = "%PDF-1.7".toByteArray()
            )
        )
    }
}
