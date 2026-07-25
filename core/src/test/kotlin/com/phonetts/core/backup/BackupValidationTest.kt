package com.phonetts.core.backup

import com.phonetts.core.audio.transform.TempoStretch
import com.phonetts.core.session.ReaderSession
import com.phonetts.core.session.SessionState
import com.phonetts.core.store.FavoriteVoiceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "A valid file with one corrupt entry" (issue #149's explicit failure case): every list here mixes
 * one good entry with one bad one, and validation must keep the good one, drop the bad one, and count
 * it - never throw, never drop the whole list. */
class BackupValidationTest {
    @Test
    fun `a blank-id library document is dropped, the rest of the library survives`() {
        val document =
            BackupDocument(
                exportedAtMillis = 1L,
                library =
                    listOf(
                        BackupLibraryDocument("doc1", "Good", "text", 1L),
                        BackupLibraryDocument("", "Bad", "text", 1L),
                    ),
            )

        val validated = BackupValidation.validate(document)

        assertEquals(listOf("doc1"), validated.library.map { it.id })
        assertEquals(1, validated.skippedCount)
    }

    @Test
    fun `a resume position with a non-finite or zero speed is dropped`() {
        val document =
            BackupDocument(
                exportedAtMillis = 1L,
                resumePositions =
                    listOf(
                        BackupResumePosition("doc1", "piper", "amy", 1.0f, 3),
                        BackupResumePosition("doc2", "piper", "amy", Float.NaN, 3),
                        BackupResumePosition("doc3", "piper", "amy", 0f, 3),
                        BackupResumePosition("doc4", "piper", "amy", -1f, 3),
                    ),
            )

        val validated = BackupValidation.validate(document)

        assertEquals(listOf("doc1"), validated.resumePositions.map { it.documentId })
        assertEquals(3, validated.skippedCount)
    }

    @Test
    fun `a blended voice with an out-of-range weight is dropped, a valid one survives`() {
        val document =
            BackupDocument(
                exportedAtMillis = 1L,
                blendedVoices =
                    listOf(
                        BackupBlendedVoice("mix1", "Good", "kokoro", "a", "b", 0.5f),
                        BackupBlendedVoice("mix2", "Bad", "kokoro", "a", "b", 1.5f),
                        BackupBlendedVoice("mix3", "AlsoBad", "kokoro", "", "b", 0.5f),
                    ),
            )

        val validated = BackupValidation.validate(document)

        assertEquals(listOf("mix1"), validated.blendedVoices.map { it.id })
        assertEquals(2, validated.skippedCount)
    }

    @Test
    fun `a benchmark record with a negative RTF is dropped, a valid one survives`() {
        val document =
            BackupDocument(
                exportedAtMillis = 1L,
                benchmarkHistory =
                    listOf(
                        BackupBenchmarkRecord("piper", "Pixel", 100L, 0.8),
                        BackupBenchmarkRecord("piper", "Pixel", 200L, -1.0),
                    ),
            )

        val validated = BackupValidation.validate(document)

        assertEquals(1, validated.benchmarkRecords.size)
        assertEquals(100L, validated.benchmarkRecords.single().timestampMillis)
        assertEquals(1, validated.skippedCount)
    }

    @Test
    fun `favorites drop blank ids and de-duplicate, without throwing`() {
        val document =
            BackupDocument(
                exportedAtMillis = 1L,
                favorites =
                    BackupFavorites(
                        voices =
                            listOf(
                                FavoriteVoiceRef("piper", "amy"),
                                FavoriteVoiceRef("piper", "amy"),
                                FavoriteVoiceRef("piper", ""),
                            ),
                        models = listOf("kokoro", "kokoro", ""),
                    ),
            )

        val validated = BackupValidation.validate(document)

        assertEquals(1, validated.favoriteVoices.size)
        assertEquals(listOf("kokoro"), validated.favoriteModels)
        assertTrue(validated.skippedCount >= 1)
    }

    @Test
    fun `an out-of-range tempo factor is clamped rather than the whole session dropped`() {
        val document =
            BackupDocument(
                exportedAtMillis = 1L,
                session = SessionState(reader = ReaderSession(tempoFactor = 99f)),
            )

        val validated = BackupValidation.validate(document)

        assertEquals(TempoStretch.MAX_FACTOR, validated.session.reader.tempoFactor)
    }

    @Test
    fun `negative usage-stat totals are floored to zero rather than the whole snapshot dropped`() {
        val document =
            BackupDocument(
                exportedAtMillis = 1L,
                usageStats =
                    BackupUsageStats(totalListeningMillis = -5L, totalWordsSpoken = -1L, generationCount = -2L),
            )

        val validated = BackupValidation.validate(document)

        assertEquals(0L, validated.usageStats.totalListeningMillis)
        assertEquals(0L, validated.usageStats.totalWordsSpoken)
        assertEquals(0L, validated.usageStats.generationCount)
    }
}
