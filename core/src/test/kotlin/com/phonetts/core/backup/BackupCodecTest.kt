package com.phonetts.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackupCodecTest {
    @Test
    fun `encode then decode round-trips every field`() {
        val document =
            BackupDocument(
                exportedAtMillis = 1_700_000_000_000L,
                library = listOf(BackupLibraryDocument("doc1", "My Book", "Once upon a time.", 100L)),
                resumePositions = listOf(BackupResumePosition("doc1", "piper", "amy", 1.2f, 3)),
                blendedVoices = listOf(BackupBlendedVoice("mix1", "Mix", "kokoro", "a", "b", 0.4f)),
                usageStats = BackupUsageStats(1_000L, 50L, 2L, mapOf("piper" to 2L)),
                benchmarkHistory = listOf(BackupBenchmarkRecord("piper", "Pixel", 5L, 0.9)),
            )

        val decoded = BackupCodec.decode(BackupCodec.encode(document))

        assertIs<BackupDecodeResult.Success>(decoded)
        assertEquals(document, decoded.document)
    }

    @Test
    fun `an empty file is refused as corrupt`() {
        assertIs<BackupDecodeResult.Corrupt>(BackupCodec.decode(""))
    }

    @Test
    fun `garbage bytes are refused as corrupt, never thrown`() {
        assertIs<BackupDecodeResult.Corrupt>(BackupCodec.decode("this is not json at all {{{"))
    }

    @Test
    fun `a truncated file is refused as corrupt`() {
        val whole = BackupCodec.encode(BackupDocument(exportedAtMillis = 1L))
        val truncated = whole.take(whole.length / 2)

        assertIs<BackupDecodeResult.Corrupt>(BackupCodec.decode(truncated))
    }

    @Test
    fun `a schema version newer than this build understands is refused, not partially applied`() {
        val futureVersion = BackupDocument.CURRENT_SCHEMA_VERSION + 1
        val raw = """{"schemaVersion":$futureVersion,"exportedAtMillis":1}"""

        val result = BackupCodec.decode(raw)

        assertIs<BackupDecodeResult.UnsupportedVersion>(result)
        assertEquals(futureVersion, result.fileVersion)
    }

    @Test
    fun `a schema version older than this build supports is refused`() {
        val ancientVersion = BackupDocument.MIN_SUPPORTED_SCHEMA_VERSION - 1
        val raw = """{"schemaVersion":$ancientVersion,"exportedAtMillis":1}"""

        val result = BackupCodec.decode(raw)

        assertIs<BackupDecodeResult.UnsupportedVersion>(result)
        assertEquals(ancientVersion, result.fileVersion)
    }

    @Test
    fun `a wrong-type schemaVersion is refused as corrupt rather than guessed at`() {
        val raw = """{"schemaVersion":"not-a-number","exportedAtMillis":1}"""

        assertIs<BackupDecodeResult.Corrupt>(BackupCodec.decode(raw))
    }
}
