package com.phonetts.engines.cosyvoice2

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The baked-voice reader ([CosyVoice2SpeakerPrompt]) is a deterministic seam, so it gets golden
 * coverage (unlike the audio): a well-formed voices.bin round-trips to the right embedding /
 * prompt tokens / mel, and a malformed one fails closed (spec §9.1) rather than reshape-guessing.
 */
class CosyVoice2SpeakerPromptTest {
    @Test
    fun `parse round-trips a well-formed baked voice`() {
        val prompt = CosyVoice2SpeakerPrompt.parse(bakedVoiceBytes(embeddingDim = 4, promptTokenCount = 2))

        checkNotNull(prompt)
        assertContentEquals(FloatArray(4) { 0.2f }, prompt.embedding)
        assertContentEquals(longArrayOf(0L, 1L), prompt.promptTokens)
        assertEquals(3, prompt.promptFeatFrames)
        assertEquals(3 * CosyVoice2Graphs.MEL_DIM, prompt.promptFeat.size)
    }

    @Test
    fun `parse fails closed on a truncated file`() {
        val truncated = bakedVoiceBytes().copyOfRange(0, 16)

        assertNull(CosyVoice2SpeakerPrompt.parse(truncated))
    }

    @Test
    fun `parse fails closed when the header undercounts the body`() {
        // Header claims 0 tokens but the body still carries the real ones -> size mismatch.
        val mismatched = bakedVoiceBytes(promptTokenCount = 2).copyOf()
        // Overwrite the promptTokenCount header field (2nd int, bytes 4..7) with 0.
        mismatched[4] = 0
        mismatched[5] = 0
        mismatched[6] = 0
        mismatched[7] = 0

        assertNull(CosyVoice2SpeakerPrompt.parse(mismatched))
    }

    @Test
    fun `fallback prompt is correctly shaped for the flow graph`() {
        val fallback = CosyVoice2SpeakerPrompt.fallback()

        assertEquals(CosyVoice2Graphs.MEL_DIM, fallback.promptFeat.size / fallback.promptFeatFrames)
        assertEquals(fallback.promptTokens.size >= 1, true)
    }
}
