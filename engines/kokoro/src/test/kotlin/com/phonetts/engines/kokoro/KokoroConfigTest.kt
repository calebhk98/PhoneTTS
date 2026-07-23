package com.phonetts.engines.kokoro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KokoroConfigTest {
    @Test
    fun modelTypeStandsInForFamilyWhenTheFamilyFieldIsAbsent() {
        // The real onnx-community Kokoro export ships exactly this - no "family" key.
        val parsed = KokoroConfig.parse("""{"model_type": "style_text_to_speech_2"}""")

        assertEquals("style_text_to_speech_2", parsed.family)
    }

    @Test
    fun anExplicitFamilyFieldWinsOverModelType() {
        val parsed = KokoroConfig.parse("""{"family": "kokoro", "model_type": "something_else"}""")

        assertEquals("kokoro", parsed.family)
    }

    @Test
    fun familyIsNullWhenNeitherFieldIsPresent() {
        val parsed = KokoroConfig.parse("""{"sample_rate": 24000}""")

        assertNull(parsed.family)
    }
}
