package com.example

import com.example.downloader.DownloadFilePlanner
import com.example.model.DuplicateAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilePlannerTest {
    @Test
    fun selectedItemsAreCheckedIndividuallyAndUniqueTitlesStayUnconflicted() {
        val plans = DownloadFilePlanner.plan(
            titles = listOf("first", "second"),
            extension = "mp3",
            suffix = "",
            existingFileNames = setOf("second.mp3")
        )

        assertEquals(2, plans.size)
        assertFalse(plans[0].conflictsWithExistingFile)
        assertTrue(plans[1].conflictsWithExistingFile)
        assertEquals("first.mp3", plans[0].targetFileName)
        assertEquals("second.mp3", plans[1].targetFileName)
        assertEquals("first.mp3", plans[0].downloadAnywayFileName)
        assertNotEquals(plans[0].downloadAnywayFileName, plans[1].downloadAnywayFileName)
    }

    @Test
    fun repeatedTitlesReceiveDistinctDestinationsAndCopyNamesCannotStealAnotherTarget() {
        val plans = DownloadFilePlanner.plan(
            titles = listOf("same title", "same title"),
            extension = "mp4",
            suffix = "_720p",
            existingFileNames = setOf("same title_720p.mp4", "same title_720p (1).mp4")
        )

        assertEquals("same title_720p.mp4", plans[0].targetFileName)
        assertEquals("same title_720p (1).mp4", plans[1].targetFileName)
        assertTrue(plans.all { it.conflictsWithExistingFile })
        assertEquals("same title_720p (2).mp4", plans[0].downloadAnywayFileName)
        assertEquals("same title_720p (1) (1).mp4", plans[1].downloadAnywayFileName)
        assertTrue(plans.map { it.downloadAnywayFileName }.toSet().size == 2)
        assertTrue(plans.none { plan -> plan.downloadAnywayFileName in plans.map { it.targetFileName } })
    }

    @Test
    fun replacementIsRestrictedToThatItemsActualConflict() {
        val plans = DownloadFilePlanner.plan(
            titles = listOf("new item", "existing item"),
            extension = "mp3",
            suffix = "",
            existingFileNames = setOf("existing item.mp3")
        )

        val unconflicted = plans[0]
        val conflicted = plans[1]
        assertEquals(DuplicateAction.DOWNLOAD_ANYWAY, unconflicted.actionFor(DuplicateAction.REPLACE_EXISTING))
        assertEquals(unconflicted.targetFileName, unconflicted.outputFileNameFor(DuplicateAction.REPLACE_EXISTING))
        assertEquals(DuplicateAction.REPLACE_EXISTING, conflicted.actionFor(DuplicateAction.REPLACE_EXISTING))
        assertEquals(conflicted.targetFileName, conflicted.outputFileNameFor(DuplicateAction.REPLACE_EXISTING))
        assertEquals(DuplicateAction.DOWNLOAD_ANYWAY, conflicted.actionFor(DuplicateAction.DOWNLOAD_ANYWAY))
        assertEquals(conflicted.downloadAnywayFileName, conflicted.outputFileNameFor(DuplicateAction.DOWNLOAD_ANYWAY))
    }

    @Test
    fun perItemExtensionsPreserveDifferentOriginalFileTypes() {
        val plans = DownloadFilePlanner.plan(
            titles = listOf("manual", "installer", "photo"),
            extension = "bin",
            suffix = "",
            existingFileNames = setOf("installer.apk"),
            extensions = listOf("pdf", "apk", "png")
        )

        assertEquals(listOf("manual.pdf", "installer.apk", "photo.png"), plans.map { it.targetFileName })
        assertEquals("installer (1).apk", plans[1].downloadAnywayFileName)
        assertTrue(plans[1].conflictsWithExistingFile)
        assertFalse(plans[0].conflictsWithExistingFile)
        assertFalse(plans[2].conflictsWithExistingFile)
    }

    @Test
    fun emptyTitlesAreSanitizedAndDistinct() {
        val plans = DownloadFilePlanner.plan(
            titles = listOf("💥", "💥"),
            extension = ".wav",
            suffix = "",
            existingFileNames = emptySet()
        )

        assertEquals(listOf("ملف.wav", "ملف (1).wav"), plans.map { it.targetFileName })
        assertTrue(plans.none { it.conflictsWithExistingFile })
    }
}
