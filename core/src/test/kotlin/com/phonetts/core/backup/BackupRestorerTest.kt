package com.phonetts.core.backup

import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.metrics.BenchmarkRecord
import com.phonetts.core.metrics.UsageStatsSnapshot
import com.phonetts.core.prefs.DocumentPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Seam tests for restore (issue #149): round-trip fidelity, merge-vs-replace semantics, and
 * idempotency - "applying the same file twice never changes the outcome a second time." */
class BackupRestorerTest {
    private fun populated(): BackupStores {
        val stores = freshStores()
        stores.library.add("doc1", "Full text.", savedAtMillis = 10L, title = "My Book")
        stores.documentMemory.record(DocumentPosition("doc1", "piper", "amy", 1.2f, 4))
        stores.favoritesStore.setFavoriteVoice("piper", "amy", favorite = true)
        stores.favoritesStore.setFavoriteModel("kokoro", favorite = true)
        stores.favoritesStore.flagModel("bad", reason = "broken", atMs = 1L)
        stores.blendedVoiceStore.save(BlendedVoiceSpec("mix1", "Mix", "kokoro", "a", "b", 0.5f))
        stores.sessionStateStore.updateReader { it.copy(text = "draft") }
        stores.usageStatsStore.recordGeneration(wordCount = 10)
        stores.benchmarkHistory.record(BenchmarkRecord("piper", "Pixel", 100L, 0.8))
        return stores
    }

    @Test
    fun `export then restore into an empty set of stores reproduces the exact same export`() {
        val original = BackupExporter.export(populated(), nowMillis = 1L)

        val restoredInto = freshStores()
        BackupRestorer.restore(original, restoredInto, RestoreMode.MERGE)

        val reExported = BackupExporter.export(restoredInto, nowMillis = 1L)
        assertEquals(original, reExported)
    }

    @Test
    fun `merge adds the file's document without deleting a local-only one`() {
        val stores = freshStores()
        stores.library.add("local-only", "kept", savedAtMillis = 1L)
        val incoming =
            BackupDocument(
                exportedAtMillis = 1L,
                library = listOf(BackupLibraryDocument("imported", "New", "text", 2L)),
            )

        val summary = BackupRestorer.restore(incoming, stores, RestoreMode.MERGE)

        assertEquals(setOf("local-only", "imported"), stores.library.list().map { it.id }.toSet())
        assertEquals(1, summary.documentsWritten)
        assertEquals(0, summary.documentsRemoved)
    }

    @Test
    fun `replace deletes a local-only document the file does not mention`() {
        val stores = freshStores()
        stores.library.add("local-only", "gone soon", savedAtMillis = 1L)
        val incoming =
            BackupDocument(
                exportedAtMillis = 1L,
                library = listOf(BackupLibraryDocument("imported", "New", "text", 2L)),
            )

        val summary = BackupRestorer.restore(incoming, stores, RestoreMode.REPLACE)

        assertEquals(listOf("imported"), stores.library.list().map { it.id })
        assertEquals(1, summary.documentsRemoved)
    }

    @Test
    fun `replace forgets the resume position of a document it just removed`() {
        val stores = freshStores()
        stores.library.add("local-only", "text", savedAtMillis = 1L)
        stores.documentMemory.record(DocumentPosition("local-only", "piper", "amy", 1f, 2))
        val incoming = BackupDocument(exportedAtMillis = 1L)

        BackupRestorer.restore(incoming, stores, RestoreMode.REPLACE)

        assertNull(stores.documentMemory.resume("local-only"))
    }

    @Test
    fun `merge is idempotent - applying the same file twice does not duplicate anything`() {
        val original = BackupExporter.export(populated(), nowMillis = 1L)
        val target = freshStores()

        BackupRestorer.restore(original, target, RestoreMode.MERGE)
        BackupRestorer.restore(original, target, RestoreMode.MERGE)

        assertEquals(original, BackupExporter.export(target, nowMillis = 1L))
    }

    @Test
    fun `replace is idempotent - applying the same file twice leaves exactly the file's contents`() {
        val original = BackupExporter.export(populated(), nowMillis = 1L)
        val target = freshStores()

        BackupRestorer.restore(original, target, RestoreMode.REPLACE)
        BackupRestorer.restore(original, target, RestoreMode.REPLACE)

        assertEquals(original, BackupExporter.export(target, nowMillis = 1L))
    }

    @Test
    fun `blended voices merge upserts by id without touching a local-only mix on another model`() {
        val stores = freshStores()
        stores.blendedVoiceStore.save(BlendedVoiceSpec("local", "Local", "kittentts", "a", "b", 0.5f))
        val incoming =
            BackupDocument(
                exportedAtMillis = 1L,
                blendedVoices = listOf(BackupBlendedVoice("mix1", "Mix", "kokoro", "a", "b", 0.5f)),
            )

        BackupRestorer.restore(incoming, stores, RestoreMode.MERGE)

        assertEquals(listOf("local"), stores.blendedVoiceStore.forModel("kittentts").map { it.id })
        assertEquals(listOf("mix1"), stores.blendedVoiceStore.forModel("kokoro").map { it.id })
    }

    @Test
    fun `blended voices replace wipes a local-only mix on a model the file never mentions`() {
        val stores = freshStores()
        stores.blendedVoiceStore.save(BlendedVoiceSpec("local", "Local", "kittentts", "a", "b", 0.5f))
        val incoming =
            BackupDocument(
                exportedAtMillis = 1L,
                blendedVoices = listOf(BackupBlendedVoice("mix1", "Mix", "kokoro", "a", "b", 0.5f)),
            )

        BackupRestorer.restore(incoming, stores, RestoreMode.REPLACE)

        assertTrue(stores.blendedVoiceStore.forModel("kittentts").isEmpty())
        assertEquals(listOf("mix1"), stores.blendedVoiceStore.forModel("kokoro").map { it.id })
    }

    @Test
    fun `usage stats merge takes the larger total, never sums and never regresses`() {
        val stores = freshStores()
        stores.usageStatsStore.restoreSnapshot(UsageStatsSnapshot(0L, 0L, 5L, emptyMap()))
        val incoming = BackupDocument(exportedAtMillis = 1L, usageStats = BackupUsageStats(generationCount = 3L))

        BackupRestorer.restore(incoming, stores, RestoreMode.MERGE)

        assertEquals(5L, stores.usageStatsStore.snapshot().generationCount)
    }

    @Test
    fun `usage stats replace adopts the file's totals exactly`() {
        val stores = freshStores()
        stores.usageStatsStore.restoreSnapshot(UsageStatsSnapshot(0L, 0L, 5L, emptyMap()))
        val incoming = BackupDocument(exportedAtMillis = 1L, usageStats = BackupUsageStats(generationCount = 3L))

        BackupRestorer.restore(incoming, stores, RestoreMode.REPLACE)

        assertEquals(3L, stores.usageStatsStore.snapshot().generationCount)
    }

    @Test
    fun `benchmark history merge unions samples across two devices without losing either`() {
        val stores = freshStores()
        stores.benchmarkHistory.record(BenchmarkRecord("piper", "Pixel", 1L, 0.5))
        val incoming =
            BackupDocument(
                exportedAtMillis = 1L,
                benchmarkHistory = listOf(BackupBenchmarkRecord("piper", "Pixel", 2L, 0.6)),
            )

        BackupRestorer.restore(incoming, stores, RestoreMode.MERGE)

        assertEquals(listOf(1L, 2L), stores.benchmarkHistory.history("piper", "Pixel").map { it.timestampMillis })
    }

    @Test
    fun `benchmark history replace clobbers a local-only key the file never mentions`() {
        val stores = freshStores()
        stores.benchmarkHistory.record(BenchmarkRecord("piper", "Pixel", 1L, 0.5))
        val incoming = BackupDocument(exportedAtMillis = 1L)

        BackupRestorer.restore(incoming, stores, RestoreMode.REPLACE)

        assertTrue(stores.benchmarkHistory.history("piper", "Pixel").isEmpty())
    }

    @Test
    fun `a corrupt individual entry is skipped but the rest of the restore still applies`() {
        val incoming =
            BackupDocument(
                exportedAtMillis = 1L,
                library =
                    listOf(
                        BackupLibraryDocument("good", "Good", "text", 1L),
                        BackupLibraryDocument("", "Bad", "text", 1L),
                    ),
            )
        val stores = freshStores()

        val summary = BackupRestorer.restore(incoming, stores, RestoreMode.MERGE)

        assertEquals(listOf("good"), stores.library.list().map { it.id })
        assertEquals(1, summary.entriesSkipped)
    }
}
