package com.phonetts.core.engine

import com.phonetts.core.testing.testDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seam tests for the voice-blend capability (issue #42): the `supportsVoiceBlend` descriptor fact
 * (SSOT — the UI derives the mix control from this, never a model-name check) and the
 * [BlendableVoices] contract (a blend-capable engine turns two source embeddings into a new
 * selectable voice, and fails closed on an unknown source voice).
 */
class BlendableVoicesTest {
    // A minimal blend-capable engine standing in for Kokoro/KittenTTS: it holds a name->embedding
    // table and mixes two rows with the shared VoiceBlend math, exactly as the real engines do.
    private class FakeBlendEngine(
        private val table: MutableMap<String, FloatArray>,
    ) : BlendableVoices {
        val blended = mutableMapOf<String, FloatArray>()

        override fun addBlendedVoice(spec: BlendedVoiceSpec): Voice? {
            val a = table[spec.voiceAId] ?: return null
            val b = table[spec.voiceBId] ?: return null
            val mixed = VoiceBlend.blend(a, b, spec.weight)
            table[spec.id] = mixed
            blended[spec.id] = mixed
            return Voice(id = spec.id, name = spec.name, language = "en")
        }
    }

    @Test
    fun `descriptor defaults to not blendable`() {
        assertTrue(!testDescriptor("m", "e").supportsVoiceBlend)
    }

    @Test
    fun `descriptor can advertise blend support`() {
        val d = testDescriptor("m", "e").copy(supportsVoiceBlend = true)
        assertTrue(d.supportsVoiceBlend)
    }

    @Test
    fun `a blendable engine registers an in-between voice from two sources`() {
        val engine =
            FakeBlendEngine(
                mutableMapOf(
                    "a" to floatArrayOf(0f, 10f),
                    "b" to floatArrayOf(4f, 0f),
                ),
            )
        val voice =
            engine.addBlendedVoice(
                BlendedVoiceSpec(
                    id = "mix",
                    name = "Mix",
                    modelId = "m",
                    voiceAId = "a",
                    voiceBId = "b",
                    weight = 0.25f,
                ),
            )

        assertEquals("mix", voice?.id)
        val mixed = engine.blended.getValue("mix")
        assertEquals(1f, mixed[0]) // 0*0.75 + 4*0.25
        assertEquals(7.5f, mixed[1]) // 10*0.75 + 0*0.25
    }

    @Test
    fun `an unknown source voice fails closed`() {
        val engine = FakeBlendEngine(mutableMapOf("a" to floatArrayOf(1f)))
        val voice =
            engine.addBlendedVoice(
                BlendedVoiceSpec(
                    id = "mix",
                    name = "Mix",
                    modelId = "m",
                    voiceAId = "a",
                    voiceBId = "ghost",
                    weight = 0.5f,
                ),
            )
        assertNull(voice)
    }
}
