package com.example

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.MediaItemRepository
import com.example.model.FileType
import com.example.model.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MediaItemRepositoryExportInstrumentedTest {
    @Test
    fun exportUsesDownloadsMediaStoreAndPublishesExactFileBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "export-${UUID.randomUUID()}.wav")
        val expected = ByteArray(128 * 1024 + 37) { index -> (index * 17).toByte() }
        source.writeBytes(expected)
        val item = MediaItem(
            id = UUID.randomUUID().toString(),
            title = source.name,
            filePath = source.absolutePath,
            durationMs = 0L,
            format = "WAV",
            fileSize = expected.size.toLong(),
            fileType = FileType.AUDIO
        )
        var exportedUri: Uri? = null

        try {
            assertTrue("Export should complete through scoped Downloads storage",
                MediaItemRepository(context).exportToPublicDownloads(item))

            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.IS_PENDING
                ),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(source.name),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                    val isPending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))
                    exportedUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                    assertEquals("Download/File Station", relativePath.trimEnd('/'))
                    assertEquals(0, isPending)
                }
            }

            val actualUri = exportedUri ?: throw AssertionError("The exported Downloads row should be queryable")
            val actualBytes = resolver.openInputStream(actualUri)?.use { it.readBytes() }
                ?: throw AssertionError("The exported file should be readable")
            assertArrayEquals(expected, actualBytes)
        } finally {
            exportedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            source.delete()
        }
    }

    @Test
    fun missingSourceIsRejectedWithoutCreatingDownloadsRow() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = File(context.cacheDir, "missing-${UUID.randomUUID()}.mp3")
        val item = MediaItem(
            id = UUID.randomUUID().toString(),
            title = missing.name,
            filePath = missing.absolutePath,
            durationMs = 0L,
            format = "MP3",
            fileSize = 0L,
            fileType = FileType.AUDIO
        )

        assertFalse(MediaItemRepository(context).exportToPublicDownloads(item))
        val cursor = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(missing.name),
            null
        ) ?: throw AssertionError("Downloads query should return a cursor")
        cursor.use { assertEquals(0, it.count) }
    }
}
