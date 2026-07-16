package com.phonetts.engines.kokoro

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct unit tests for [KokoroVoiceTable]: deriving [com.phonetts.core.engine.Voice] metadata
 * from a `.bin` file NAME alone (no bytes needed), and decoding a real `.bin` file's bytes into a
 * full [KokoroVoiceTable.Entry].
 */
class KokoroVoiceTableTest {
    @Test
    fun voiceForDerivesLanguageFromTheIdPrefix() {
        assertEquals("en-us", KokoroVoiceTable.voiceFor("af_heart").language)
        assertEquals("en-gb", KokoroVoiceTable.voiceFor("bf_emma").language)
        assertEquals("ja", KokoroVoiceTable.voiceFor("jf_alpha").language)
        assertEquals("cmn", KokoroVoiceTable.voiceFor("zf_xiaoyi").language)
    }

    @Test
    fun voiceForFallsBackToTheDefaultLanguageForAnUnrecognizedPrefix() {
        assertEquals(KokoroVoiceTable.DEFAULT_LANGUAGE, KokoroVoiceTable.voiceFor("xx_mystery").language)
    }

    @Test
    fun voiceForUsesTheIdAsBothIdAndDisplayName() {
        val voice = KokoroVoiceTable.voiceFor("af_heart")

        assertEquals("af_heart", voice.id)
        assertEquals("af_heart", voice.name)
    }

    @Test
    fun entryForDecodesAWellSizedBinFile() {
        val table = KokoroBinFixtures.tableWithRowMarkers()

        val entry = KokoroVoiceTable.entryFor("af_heart", KokoroBinFixtures.bytesFor(table))

        assertEquals("af_heart", entry?.voice?.id)
        assertContentEquals(table, entry?.table)
    }

    @Test
    fun entryForReturnsNullForAWrongSizedFile() {
        assertNull(KokoroVoiceTable.entryFor("af_heart", ByteArray(10)))
    }

    @Test
    fun parseDecodesEveryWellSizedVoiceFile() {
        val heartTable = KokoroBinFixtures.uniformTable(0.1f)
        val emmaTable = KokoroBinFixtures.uniformTable(0.4f)
        val voiceFiles =
            mapOf(
                "af_heart" to KokoroBinFixtures.bytesFor(heartTable),
                "bf_emma" to KokoroBinFixtures.bytesFor(emmaTable),
            )

        val entries = KokoroVoiceTable.parse(voiceFiles)

        assertEquals(setOf("af_heart", "bf_emma"), entries.map { it.voice.id }.toSet())
        assertContentEquals(heartTable, entries.single { it.voice.id == "af_heart" }.table)
        assertContentEquals(emmaTable, entries.single { it.voice.id == "bf_emma" }.table)
    }

    @Test
    fun parseSkipsAMalformedFileButKeepsTheRest() {
        val goodTable = KokoroBinFixtures.tableWithRowMarkers()
        val voiceFiles =
            mapOf(
                "af_heart" to KokoroBinFixtures.bytesFor(goodTable),
                "corrupt_voice" to ByteArray(4),
            )

        val entries = KokoroVoiceTable.parse(voiceFiles)

        assertEquals(1, entries.size)
        assertEquals("af_heart", entries.single().voice.id)
    }

    @Test
    fun parseOfAnEmptyMapYieldsNoEntries() {
        assertTrue(KokoroVoiceTable.parse(emptyMap()).isEmpty())
    }
}
