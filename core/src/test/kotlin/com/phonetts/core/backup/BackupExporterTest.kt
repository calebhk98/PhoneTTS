package com.phonetts.core.backup

import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.metrics.BenchmarkRecord
import com.phonetts.core.prefs.DocumentPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupExporterTest {
    @Test
    fun `an empty set of stores exports an empty, but valid, document`() {
        val document = BackupExporter.export(freshStores(), nowMillis = 1L)

        assertEquals(1L, document.exportedAtMillis)
        assertEquals(BackupDocument.CURRENT_SCHEMA_VERSION, document.schemaVersion)
        assertFalse(document.modelWeightsIncluded, "model weights must never be claimed as included")
        assertTrue(document.library.isEmpty())
        assertTrue(document.resumePositions.isEmpty())
        assertTrue(document.blendedVoices.isEmpty())
        assertTrue(document.benchmarkHistory.isEmpty())
    }

    @Test
    fun `exports the reading library with full text, and only documents that have one`() {
        val stores = freshStores()
        stores.library.add("doc1", "Full text here.", savedAtMillis = 10L, title = "My Book")

        val document = BackupExporter.export(stores, nowMillis = 1L)

        assertEquals(listOf(BackupLibraryDocument("doc1", "My Book", "Full text here.", 10L)), document.library)
    }

    @Test
    fun `exports a resume position only for documents that have both a library entry and a position`() {
        val stores = freshStores()
        stores.library.add("doc1", "text", savedAtMillis = 1L)
        stores.library.add("doc2", "text2", savedAtMillis = 2L)
        stores.documentMemory.record(DocumentPosition("doc1", "piper", "amy", 1.0f, 5))
        // doc2 has no recorded position - must not appear.

        val document = BackupExporter.export(stores, nowMillis = 1L)

        assertEquals(listOf(BackupResumePosition("doc1", "piper", "amy", 1.0f, 5)), document.resumePositions)
    }

    @Test
    fun `exports favorites, blended voices, session, usage stats, and benchmark history from every store`() {
        val stores = freshStores()
        stores.favoritesStore.setFavoriteVoice("piper", "amy", favorite = true)
        stores.favoritesStore.setFavoriteModel("kokoro", favorite = true)
        stores.favoritesStore.flagModel("bad", reason = "broken", atMs = 3L)
        stores.blendedVoiceStore.save(BlendedVoiceSpec("mix1", "Mix", "kokoro", "a", "b", 0.5f))
        stores.blendedVoiceStore.save(BlendedVoiceSpec("mix2", "Mix2", "kittentts", "c", "d", 0.2f))
        stores.sessionStateStore.updateReader { it.copy(text = "draft") }
        stores.usageStatsStore.recordGeneration(wordCount = 12)
        stores.benchmarkHistory.record(BenchmarkRecord("piper", "Pixel", 100L, 0.8))

        val document = BackupExporter.export(stores, nowMillis = 1L)

        assertEquals(listOf("piper" to "amy"), document.favorites.voices.map { it.modelId to it.voiceId })
        assertEquals(listOf("kokoro"), document.favorites.models)
        assertEquals("broken", document.favorites.flagged.single().reason)
        assertEquals(setOf("mix1", "mix2"), document.blendedVoices.map { it.id }.toSet())
        assertEquals("draft", document.session.reader.text)
        assertEquals(1L, document.usageStats.generationCount)
        assertEquals(listOf(BackupBenchmarkRecord("piper", "Pixel", 100L, 0.8)), document.benchmarkHistory)
    }
}
