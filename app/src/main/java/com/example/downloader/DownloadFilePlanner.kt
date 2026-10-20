package com.example.downloader

import com.example.model.DuplicateAction
import java.util.Locale

/** A collision-aware, deterministic destination plan for one selected download item. */
data class DownloadFilePlan(
    val targetFileName: String,
    val downloadAnywayFileName: String,
    val conflictsWithExistingFile: Boolean
) {
    fun actionFor(userChoice: DuplicateAction): DuplicateAction =
        if (conflictsWithExistingFile && userChoice == DuplicateAction.REPLACE_EXISTING) {
            DuplicateAction.REPLACE_EXISTING
        } else {
            DuplicateAction.DOWNLOAD_ANYWAY
        }

    fun outputFileNameFor(action: DuplicateAction): String =
        if (conflictsWithExistingFile && action == DuplicateAction.REPLACE_EXISTING) {
            targetFileName
        } else {
            downloadAnywayFileName
        }
}

/**
 * Plans distinct destinations for each selected item before asking about any conflicts.
 * Replacement destinations always refer to a file that existed when planning began; the
 * save-as-copy destination is separately reserved so one item cannot consume another's file.
 */
object DownloadFilePlanner {
    fun plan(
        titles: List<String>,
        extension: String,
        suffix: String,
        existingFileNames: Set<String>,
        extensions: List<String>? = null
    ): List<DownloadFilePlan> {
        val normalizedExtensions = (extensions ?: List(titles.size) { extension })
            .map { it.removePrefix(".").lowercase(Locale.ROOT) }
        if (normalizedExtensions.size != titles.size || normalizedExtensions.any { ext ->
                ext.isBlank() || ext.length > 12 || ext.any { !it.isLetterOrDigit() }
            }) return emptyList()

        val targetNames = mutableListOf<Pair<String, String>>()
        val reservedTargetNames = mutableSetOf<String>()
        for ((title, normalizedExtension) in titles.zip(normalizedExtensions)) {
            val cleanTitle = title
                .replace(Regex("[^a-zA-Z0-9أ-ي_\\-\\s]"), "")
                .trim()
                .ifEmpty { "ملف" }
            val baseName = "$cleanTitle$suffix"
            var candidateBase = baseName
            var counter = 1
            while ("$candidateBase.$normalizedExtension" in reservedTargetNames) {
                candidateBase = "$baseName ($counter)"
                counter++
            }
            val candidate = "$candidateBase.$normalizedExtension"
            targetNames += candidate to normalizedExtension
            reservedTargetNames += candidate
        }

        val reservedAnywayNames = mutableSetOf<String>()
        return targetNames.map { (targetName, normalizedExtension) ->
            val conflicts = targetName in existingFileNames
            val anywayName = if (!conflicts) {
                targetName
            } else {
                val baseName = targetName.removeSuffix(".$normalizedExtension")
                var counter = 1
                var candidate: String
                do {
                    candidate = "$baseName ($counter).$normalizedExtension"
                    counter++
                } while (candidate in existingFileNames ||
                    candidate in reservedTargetNames ||
                    candidate in reservedAnywayNames)
                reservedAnywayNames += candidate
                candidate
            }
            DownloadFilePlan(
                targetFileName = targetName,
                downloadAnywayFileName = anywayName,
                conflictsWithExistingFile = conflicts
            )
        }
    }
}
