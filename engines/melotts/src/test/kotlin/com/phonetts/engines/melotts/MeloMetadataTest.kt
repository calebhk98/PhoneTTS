package com.phonetts.engines.melotts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proves [MeloMetadata] reads the real `metadata.json` shape and its melo-vits fingerprint. */
class MeloMetadataTest {
    @Test
    fun `parse reads every field the engine relies on`() {
        val text =
            """{"model_type":"melo-vits","language_code":"en","add_blank":1,"n_speakers":5,
               "sample_rate":44100,"speaker_id":0,"lang_id":2,"tone_start":7}"""

        val metadata = requireNotNull(MeloMetadata.parse(text))

        assertEquals("melo-vits", metadata.modelType)
        assertEquals("en", metadata.languageCode)
        assertEquals(5, metadata.nSpeakers)
        assertEquals(44_100, metadata.sampleRate)
        assertEquals(0, metadata.speakerId)
    }

    @Test
    fun `isMeloVits is true when model_type is melo-vits`() {
        val metadata = requireNotNull(MeloMetadata.parse("""{"model_type":"melo-vits"}"""))

        assertTrue(metadata.isMeloVits())
    }

    @Test
    fun `isMeloVits is true when the comment field mentions melo`() {
        val metadata = requireNotNull(MeloMetadata.parse("""{"comment":"exported from MeloTTS"}"""))

        assertTrue(metadata.isMeloVits())
    }

    @Test
    fun `isMeloVits is false when neither field identifies melo`() {
        val metadata = requireNotNull(MeloMetadata.parse("""{"model_type":"vits","n_speakers":1}"""))

        assertFalse(metadata.isMeloVits())
    }

    @Test
    fun `parse returns null for malformed JSON, failing closed`() {
        assertNull(MeloMetadata.parse("{not json"))
    }

    @Test
    fun `parse returns null for a valid JSON document that is not an object`() {
        assertNull(MeloMetadata.parse("[1, 2, 3]"))
    }
}
