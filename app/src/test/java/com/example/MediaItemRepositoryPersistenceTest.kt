package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.MediaItemRepository
import com.example.model.FileType
import com.example.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemRepositoryPersistenceTest {
    @Test
    fun concurrentMutationsAreSerializedAndAllItemsSurviveReload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storageFile = cleanStorageFile(context)
        val repository = MediaItemRepository(context, storageFile)
        val items = (0 until 16).map { createItem(context.cacheDir, it) }

        val results = coroutineScope {
            items.map { item -> async(Dispatchers.Default) { repository.addItem(item) } }.awaitAll()
        }

        assertTrue("Every serialized persistence operation should succeed", results.all { it })
        assertEquals(items.size, repository.itemsFlow.value.size)
        val restored = MediaItemRepository(context, storageFile)
        assertEquals(items.map { it.id }.toSet(), restored.itemsFlow.value.map { it.id }.toSet())
        assertEquals(null, restored.storageError.value)
    }

    @Test
    fun atomicBackupRestoresLibraryWhenMainSnapshotIsCorrupt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storageFile = cleanStorageFile(context)
        val item = createItem(context.cacheDir, 1)
        val repository = MediaItemRepository(context, storageFile)
        assertTrue(repository.addItem(item))

        val backupFile = File(storageFile.path + ".bak")
        storageFile.copyTo(backupFile, overwrite = true)
        storageFile.writeText("not valid JSON")

        val restored = MediaItemRepository(context, storageFile)

        assertEquals(listOf(item.id), restored.itemsFlow.value.map { it.id })
        assertEquals(null, restored.storageError.value)
        assertTrue("AtomicFile should replace the corrupt base with its recovered backup", storageFile.readText().startsWith("["))
    }

    @Test
    fun failedWriteReportsErrorAndKeepsCurrentInMemoryItems() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = File(context.cacheDir, "blocked-library-${UUID.randomUUID()}").apply { mkdirs() }
        val storageFile = File(parent, "media_library.json")
        val repository = MediaItemRepository(context, storageFile)
        val existing = createItem(context.cacheDir, 1)
        assertTrue(repository.addItem(existing))

        val movedParent = File(parent.path + ".moved")
        assertTrue(parent.renameTo(movedParent))
        parent.writeText("not a directory anymore")
        val incoming = createItem(context.cacheDir, 2)

        assertFalse(repository.addItem(incoming))
        assertEquals(listOf(existing.id), repository.itemsFlow.value.map { it.id })
        assertNotNull(repository.storageError.value)

        parent.delete()
        movedParent.deleteRecursively()
        File(existing.filePath).delete()
        File(incoming.filePath).delete()
    }

    @Test
    fun unreadableSnapshotIsPreservedAndReported() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storageFile = cleanStorageFile(context)
        storageFile.writeText("truncated JSON")

        val repository = MediaItemRepository(context, storageFile)

        assertTrue(repository.itemsFlow.value.isEmpty())
        assertNotNull(repository.storageError.value)
        assertEquals("truncated JSON", storageFile.readText())
    }

    private fun cleanStorageFile(context: android.content.Context): File {
        val storage = File(context.filesDir, "media_library.json")
        storage.delete()
        File(storage.path + ".bak").delete()
        return storage
    }

    private fun createItem(directory: File, index: Int): MediaItem {
        val file = File(directory, "library-persistence-${UUID.randomUUID()}.wav")
        file.writeText("audio-$index")
        return MediaItem(
            id = "item-$index-${UUID.randomUUID()}",
            title = "Audio $index",
            filePath = file.absolutePath,
            durationMs = 1_000L,
            format = "WAV",
            fileSize = file.length(),
            fileType = FileType.AUDIO
        )
    }
}
