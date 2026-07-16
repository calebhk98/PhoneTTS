package com.phonetts.engines.kittentts

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Direct unit tests for the `voices.npz` ZIP-of-`.npy` parser [KittenVoiceTable]. */
class KittenVoiceTableTest {
    @Test
    fun parsesEachVoiceWithItsOwnEmbeddingKeyedByEntryNameMinusNpySuffix() {
        val heart = FloatArray(256) { 0.1f }
        val emma = FloatArray(256) { -0.2f }
        val npz = NpzFixtures.npzBytes(mapOf("expr-voice-2-m" to heart, "expr-voice-2-f" to emma))

        val entries = KittenVoiceTable.parse(npz)

        assertEquals(2, entries.size)
        val byId = entries.associateBy { it.voice.id }
        assertEquals("expr-voice-2-m", byId.getValue("expr-voice-2-m").voice.name)
        assertContentEquals(heart, byId.getValue("expr-voice-2-m").embedding)
        assertContentEquals(emma, byId.getValue("expr-voice-2-f").embedding)
    }

    @Test
    fun parsesAllEightRealVoiceNames() {
        val voices = KittenEngine.VOICE_NAMES.associateWith { FloatArray(256) }
        val npz = NpzFixtures.npzBytes(voices)

        val entries = KittenVoiceTable.parse(npz)

        assertEquals(KittenEngine.VOICE_NAMES.toSet(), entries.map { it.voice.id }.toSet())
    }

    @Test
    fun ignoresNonNpyEntriesInsideTheArchive() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("not a voice embedding".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("expr-voice-2-m.npy"))
            zip.write(NpzFixtures.npyBytes(FloatArray(256)))
            zip.closeEntry()
        }

        val entries = KittenVoiceTable.parse(out.toByteArray())

        assertEquals(1, entries.size)
        assertEquals("expr-voice-2-m", entries.single().voice.id)
    }

    @Test
    fun emptyArchiveYieldsNoEntries() {
        assertTrue(KittenVoiceTable.parse(NpzFixtures.npzBytes(emptyMap())).isEmpty())
    }

    @Test
    fun malformedBytesYieldNoEntriesRatherThanThrowing() {
        assertTrue(KittenVoiceTable.parse("not a zip at all".toByteArray()).isEmpty())
    }
}
