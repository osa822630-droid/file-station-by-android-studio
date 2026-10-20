package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioTranscoder
import com.example.downloader.MediaDownloader
import com.example.model.AudioFormat
import com.example.model.FileType
import com.example.model.PlaylistItem
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaDownloaderFailureTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `non-success HTTP response does not create substitute media`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream unavailable"))

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val title = "failed-download-${UUID.randomUUID()}"
        val outputFile = File(context.filesDir, "audio_downloads/$title.mp4")
        val downloader = MediaDownloader(context, AudioTranscoder(context))
        val item = PlaylistItem(
            id = UUID.randomUUID().toString(),
            title = title,
            url = server.url("/media.mp4").toString(),
            fileType = FileType.VIDEO,
            isVideo = true
        )

        val result = downloader.downloadAndProcessItem(
            item = item,
            targetFormat = AudioFormat.MP4,
            targetSpeed = 1.0f,
            onDownloadProgress = { _, _, _ -> },
            onSpeedProgress = { _, _ -> }
        )

        assertNull("A failed HTTP response must be reported as a failed download", result)
        assertFalse("A failed download must not leave a generated output file", outputFile.exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `audio source is not relabeled as a video container`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not requested"))

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val title = "audio-as-video-${UUID.randomUUID()}"
        val outputFile = File(context.filesDir, "audio_downloads/$title.mp4")
        val downloader = MediaDownloader(context, AudioTranscoder(context))
        val item = PlaylistItem(
            id = UUID.randomUUID().toString(),
            title = title,
            url = server.url("/audio.wav").toString(),
            fileType = FileType.AUDIO,
            isVideo = false
        )

        val result = downloader.downloadAndProcessItem(
            item = item,
            targetFormat = AudioFormat.MP4,
            targetSpeed = 1.0f,
            onDownloadProgress = { _, _, _ -> },
            onSpeedProgress = { _, _ -> }
        )

        assertNull("Audio bytes must not be saved with a video extension", result)
        assertFalse(outputFile.exists())
        assertEquals("Reject the mismatch before making a network request", 0, server.requestCount)
    }

    @Test
    fun `failed replacement keeps previous download intact`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not a video container"))

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadsDir = File(context.filesDir, "audio_downloads").apply { mkdirs() }
        val title = "preserve-replaced-video-${UUID.randomUUID()}"
        val outputFile = File(downloadsDir, "$title.mp4")
        val previousBytes = "existing-valid-video-placeholder".toByteArray()
        outputFile.writeBytes(previousBytes)
        val item = PlaylistItem(
            id = UUID.randomUUID().toString(),
            title = title,
            url = server.url("/broken.mp4").toString(),
            fileType = FileType.VIDEO,
            isVideo = true
        )

        try {
            val result = MediaDownloader(context, AudioTranscoder(context)).downloadAndProcessItem(
                item = item,
                targetFormat = AudioFormat.MP4,
                targetSpeed = 1.0f,
                duplicateAction = com.example.model.DuplicateAction.REPLACE_EXISTING,
                onDownloadProgress = { _, _, _ -> },
                onSpeedProgress = { _, _ -> }
            )

            assertNull("An invalid replacement source must fail", result)
            assertTrue("The previous file must survive a failed replacement", outputFile.readBytes().contentEquals(previousBytes))
            assertTrue(
                "Failed replacement must remove its staging file",
                downloadsDir.listFiles().orEmpty().none { it.name.startsWith(".$title.") }
            )
            assertEquals(1, server.requestCount)
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `html response with video suffix is rejected and never written as media`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<!doctype html><html><body>not found</body></html>")
        )

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val title = "html-masquerading-as-video-${UUID.randomUUID()}"
        val outputFile = File(context.filesDir, "audio_downloads/$title.mp4")
        val item = PlaylistItem(
            id = UUID.randomUUID().toString(),
            title = title,
            url = server.url("/clip.mp4").toString(),
            fileType = FileType.VIDEO,
            isVideo = true,
            sourceMimeType = "video/mp4",
            sourceFileName = "clip.mp4"
        )

        val result = MediaDownloader(context, AudioTranscoder(context)).downloadAndProcessItem(
            item = item,
            targetFormat = AudioFormat.MP4,
            targetSpeed = 1.0f,
            onDownloadProgress = { _, _, _ -> },
            onSpeedProgress = { _, _ -> }
        )

        assertNull("An HTML page must not be accepted as a video", result)
        assertFalse(outputFile.exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `original format keeps a valid public PDF using range probe metadata`() = runBlocking {
        val id = UUID.randomUUID().toString()
        val sourceName = "manual-$id.pdf"
        val pdfBytes = "%PDF-1.7\nOriginal test PDF bytes".toByteArray(Charsets.US_ASCII)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Length", "42")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "application/pdf")
                .setHeader("Content-Disposition", "attachment; filename=$sourceName")
                .setHeader("Content-Range", "bytes 0-0/42")
                .setBody("%")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/pdf")
                .setHeader("Content-Disposition", "attachment; filename=$sourceName")
                .setBody(String(pdfBytes, Charsets.US_ASCII))
        )

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloader = MediaDownloader(context, AudioTranscoder(context))
        val inspected = downloader.inspectUrl(server.url("/download").toString())
        val item = inspected.items.single()
        assertEquals(FileType.DOCUMENT, item.fileType)
        assertEquals("application/pdf", item.sourceMimeType)
        assertEquals(sourceName, item.sourceFileName)

        val downloadsDir = File(context.filesDir, "audio_downloads")
        val outputFile = File(downloadsDir, sourceName)
        val result = downloader.downloadAndProcessItem(
            item = item,
            targetFormat = AudioFormat.ORIGINAL,
            targetSpeed = 1.0f,
            onDownloadProgress = { _, _, _ -> },
            onSpeedProgress = { _, _ -> }
        )

        val downloaded = result ?: throw AssertionError("The PDF should be saved without audio/video transcoding")
        assertTrue(outputFile.isFile)
        assertTrue(outputFile.readBytes().contentEquals(pdfBytes))
        assertEquals(FileType.DOCUMENT, downloaded.fileType)
        assertEquals("PDF", downloaded.format)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `playlist extraction failure returns no invented entries`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><title>Unavailable playlist - YouTube</title></html>")
        )

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = MediaDownloader(context, AudioTranscoder(context))
            .inspectUrl(server.url("/playlist?list=PLtest123").toString())

        assertTrue(result.isPlaylist)
        assertTrue("An unavailable playlist must not be filled with fabricated items", result.items.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `playlist items without parsed metadata show unknown duration`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<title>Real playlist - YouTube</title>{"videoId":"abcdefghijk"}{"videoId":"lmnopqrstuv"}""")
        )

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = MediaDownloader(context, AudioTranscoder(context))
            .inspectUrl(server.url("/playlist?list=PLtest456").toString())

        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.durationSeconds == 0L && it.durationText == "--:--" })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `empty successful response is not reported as a completed file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val title = "empty-download-${UUID.randomUUID()}"
        val outputFile = File(context.filesDir, "audio_downloads/$title.mp4")
        val downloader = MediaDownloader(context, AudioTranscoder(context))
        val item = PlaylistItem(
            id = UUID.randomUUID().toString(),
            title = title,
            url = server.url("/empty.mp4").toString(),
            fileType = FileType.VIDEO,
            isVideo = true
        )

        val result = downloader.downloadAndProcessItem(
            item = item,
            targetFormat = AudioFormat.MP4,
            targetSpeed = 1.0f,
            onDownloadProgress = { _, _, _ -> },
            onSpeedProgress = { _, _ -> }
        )

        assertNull("An empty response must not be treated as a media file", result)
        assertFalse("An empty response must not leave an output file", outputFile.exists())
        assertEquals(1, server.requestCount)
    }
}
