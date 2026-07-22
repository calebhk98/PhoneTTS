package com.phonetts.core.storage

import java.io.File

/**
 * Moves a downloaded-models tree from one base directory to another when the storage location
 * changes (issue #4/#5, data-loss bug). Deliberately `:core`-pure — only `java.io.File`, no
 * Android types — so the logic (and its failure handling) is provable on a plain JVM.
 *
 * The bug this fixes: switching (or even re-picking the SAME) storage folder used to clear the
 * catalog and re-scan only the NEW base dir, with nothing ever moving the model folders that were
 * physically sitting under the OLD one. They became permanently unreachable, while the catalog
 * still called them "known" (from a stale entry, or a re-import that could no longer find the
 * files) — installed-but-undeletable, undeletable-but-not-reinstallable. [migrate] is the missing
 * step: it must run BEFORE the caller clears/re-hydrates the catalog against the new location.
 */
object ModelStorageMigrator {
    /** What happened when [migrate] tried to move [oldModelsDir]'s contents to [newModelsDir]. */
    sealed interface Outcome {
        /** [oldModelsDir] and [newModelsDir] already resolve to the same real location — nothing to do. */
        data object SameLocation : Outcome

        /** Nothing existed under [oldModelsDir] to move (e.g. first-ever pick, nothing downloaded yet). */
        data object NothingToMigrate : Outcome

        /** Every entry moved cleanly. */
        data class Migrated(val movedCount: Int) : Outcome

        /**
         * At least one top-level entry failed to migrate. [failedNames] are left completely
         * untouched under the OLD location (fail safe, per rule 1 — never delete a source that
         * wasn't confirmed copied) so nothing is lost; the caller surfaces this to the user.
         */
        data class PartialFailure(val movedCount: Int, val failedNames: List<String>) : Outcome
    }

    /**
     * True if [a] and [b] refer to the same real directory — e.g. re-picking the identical folder,
     * possibly resolved to a slightly different (but equivalent) path string. Falls back to plain
     * absolute-path equality if canonicalization fails (e.g. a transient I/O error), which is still
     * strictly more accurate than never checking at all.
     */
    fun sameLocation(
        a: File,
        b: File,
    ): Boolean = runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(a.absoluteFile == b.absoluteFile)

    /**
     * Moves every top-level entry of [oldModelsDir] into [newModelsDir]. Prefers an atomic
     * [File.renameTo] (same volume); falls back to a recursive copy that is verified (size + file
     * count) before the source is deleted (cross-volume, e.g. moving onto/off an SD card).
     *
     * A same-location pair (rule 2 — re-picking the identical folder must be a no-op) short-circuits
     * before touching anything. A per-entry failure never deletes that entry's source (rule 1).
     */
    fun migrate(
        oldModelsDir: File,
        newModelsDir: File,
    ): Outcome {
        if (sameLocation(oldModelsDir, newModelsDir)) return Outcome.SameLocation
        val children = oldModelsDir.takeIf { it.isDirectory }?.listFiles() ?: return Outcome.NothingToMigrate
        if (children.isEmpty()) return Outcome.NothingToMigrate
        if (!newModelsDir.isDirectory && !newModelsDir.mkdirs()) {
            return Outcome.PartialFailure(movedCount = 0, failedNames = children.map { it.name })
        }
        return migrateChildren(children, newModelsDir)
    }

    private fun migrateChildren(
        children: Array<File>,
        newModelsDir: File,
    ): Outcome {
        val failed = mutableListOf<String>()
        var moved = 0
        children.forEach { child ->
            if (migrateOne(child, File(newModelsDir, child.name))) moved++ else failed.add(child.name)
        }
        return if (failed.isEmpty()) Outcome.Migrated(moved) else Outcome.PartialFailure(moved, failed)
    }

    // Already present at the destination (e.g. a retried migration after a previous partial
    // failure moved everything else) counts as done for this entry — nothing lost, nothing
    // clobbered; the leftover source is harmless and left alone rather than risking a bad delete.
    private fun migrateOne(
        src: File,
        dest: File,
    ): Boolean {
        if (dest.exists()) return true
        if (src.renameTo(dest)) return true
        return copyThenVerifyThenDeleteSource(src, dest)
    }

    private fun copyThenVerifyThenDeleteSource(
        src: File,
        dest: File,
    ): Boolean {
        val copied = runCatching { copyTree(src, dest) }.getOrDefault(false)
        if (!copied || !treesMatch(src, dest)) {
            dest.deleteRecursively()
            return false
        }
        return src.deleteRecursively()
    }

    private fun copyTree(
        src: File,
        dest: File,
    ): Boolean {
        if (!src.isDirectory) return runCatching { src.copyTo(dest, overwrite = false) }.isSuccess
        if (!dest.mkdirs() && !dest.isDirectory) return false
        val kids = src.listFiles() ?: return true
        return kids.all { copyTree(it, File(dest, it.name)) }
    }

    // Verified by size + file count rather than a byte-for-byte re-read (cheap enough for a
    // relocation the user triggers rarely, and enough to catch a truncated/partial copy).
    private fun treesMatch(
        a: File,
        b: File,
    ): Boolean = treeSize(a) == treeSize(b) && treeFileCount(a) == treeFileCount(b)

    private fun treeSize(f: File): Long = if (f.isDirectory) (f.listFiles()?.sumOf(::treeSize) ?: 0L) else f.length()

    private fun treeFileCount(f: File): Int =
        if (f.isDirectory) (f.listFiles()?.sumOf(::treeFileCount) ?: 0) else 1
}
