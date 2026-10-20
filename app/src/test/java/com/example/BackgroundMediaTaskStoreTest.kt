package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.background.BackgroundMediaTaskStore
import com.example.background.DownloadRequest
import com.example.background.StoredMediaTask
import com.example.model.AudioBitrate
import com.example.model.AudioFormat
import com.example.model.FileType
import com.example.model.PlaylistItem
import com.example.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundMediaTaskStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun downloadBatchDescriptorRestoresOptionsAndEachExactOutputName() {
        val id = UUID.randomUUID().toString()
        val item = PlaylistItem(
            id = "video-id",
            title = "ملف تجريبي",
            author = "قناة",
            durationText = "01:23",
            durationSeconds = 83,
            thumbnailUrl = "https://example.test/image.jpg",
            url = "https://example.test/watch?v=video-id",
            isVideo = true,
            fileType = FileType.VIDEO,
            fileSizeEstimate = 12345,
            selectedQuality = VideoQuality.Q480P,
            directStreamUrl = "https://cdn.example.test/source.mp4?token=abc",
            sourceMimeType = "video/mp4",
            sourceFileName = "source.mp4"
        )
        val task = StoredMediaTask.DownloadBatch(
            id = id,
            createdAt = 123456789L,
            title = item.title,
            format = AudioFormat.MP3,
            speed = 1.5f,
            quality = VideoQuality.Q480P,
            bitrate = AudioBitrate.B192,
            requests = listOf(
                DownloadRequest(item, "ملف تجريبي (1).mp3", "stable-media-id")
            )
        )

        val descriptor = BackgroundMediaTaskStore.write(context, task)
        val restored = BackgroundMediaTaskStore.read(descriptor)

        assertTrue("Task descriptors must live in persistent app files, not cache", descriptor.path.startsWith(context.filesDir.path))
        assertEquals(task, restored)
        BackgroundMediaTaskStore.delete(descriptor, restored)
    }

    @Test
    fun localConversionDescriptorRestoresAnOpenDocumentUriForWorkerSideStaging() {
        val task = StoredMediaTask.LocalConversion(
            id = UUID.randomUUID().toString(),
            createdAt = 987654321L,
            title = "تحويل محلي",
            sourcePath = "",
            sourceUri = "content://provider/document/42",
            outputPath = File(context.filesDir, "converted_audio/output.mp3").absolutePath,
            originalDurationMs = 145000L,
            isVideoSource = true,
            speed = 0.75f,
            format = AudioFormat.MP3,
            quality = VideoQuality.Q360P,
            bitrate = AudioBitrate.B256
        )

        val descriptor = BackgroundMediaTaskStore.write(context, task)
        val restored = BackgroundMediaTaskStore.read(descriptor)

        assertEquals(task, restored)
        BackgroundMediaTaskStore.markPaused(descriptor)
        assertTrue(BackgroundMediaTaskStore.isPaused(descriptor))
        BackgroundMediaTaskStore.clearPaused(descriptor)
        assertFalse(BackgroundMediaTaskStore.isPaused(descriptor))
        BackgroundMediaTaskStore.delete(descriptor, restored)
    }

    @Test
    fun localConversionDescriptorAlsoRoundTripsAStagedFileFallback() {
        val task = StoredMediaTask.LocalConversion(
            id = UUID.randomUUID().toString(),
            createdAt = 987654322L,
            title = "مصدر مرحلي",
            sourcePath = File(context.filesDir, "media_work/sources/source.mkv").absolutePath,
            sourceUri = "content://provider/document/43",
            outputPath = File(context.filesDir, "converted_audio/output.mkv").absolutePath,
            originalDurationMs = 82000L,
            isVideoSource = true,
            speed = 1f,
            format = AudioFormat.MKV,
            quality = VideoQuality.Q720P,
            bitrate = AudioBitrate.B128
        )

        val descriptor = BackgroundMediaTaskStore.write(context, task)
        val restored = BackgroundMediaTaskStore.read(descriptor)

        assertEquals(task, restored)
        BackgroundMediaTaskStore.delete(descriptor, restored)
    }
}
