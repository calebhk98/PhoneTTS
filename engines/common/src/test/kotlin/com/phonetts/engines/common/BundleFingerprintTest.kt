package com.phonetts.engines.common

import com.phonetts.core.model.ModelBundle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundleFingerprintTest {
    private fun bundleWithConfig(config: String?): ModelBundle =
        ModelBundle(
            id = "b",
            fileNames = if (config != null) setOf("config.json") else emptySet(),
            sideFiles = config?.let { mapOf("config.json" to it) } ?: emptyMap(),
        )

    @Test
    fun sideFileContainsMarkerIsTrueOnlyWhenTheSideFileExistsAndMentionsTheMarker() {
        assertTrue(bundleWithConfig("family: kitten_tts").sideFileContainsMarker("config.json", "kitten_tts"))
        assertFalse(bundleWithConfig("family: other").sideFileContainsMarker("config.json", "kitten_tts"))
        assertFalse(bundleWithConfig(null).sideFileContainsMarker("config.json", "kitten_tts"))
    }

    @Test
    fun sideFileContainsMarkerIsCaseInsensitive() {
        assertTrue(bundleWithConfig("FAMILY: KITTEN_TTS").sideFileContainsMarker("config.json", "kitten_tts"))
    }

    @Test
    fun sideFileContainsAnyMarkerIsTrueWhenAnyMarkerIsPresent() {
        val bundle = bundleWithConfig("model_type: qwen2lm")
        assertTrue(bundle.sideFileContainsAnyMarker("config.json", listOf("cosyvoice2", "qwen2lm")))
        assertFalse(bundle.sideFileContainsAnyMarker("config.json", listOf("piper", "kokoro")))
        assertFalse(bundleWithConfig(null).sideFileContainsAnyMarker("config.json", listOf("cosyvoice2")))
    }
}
