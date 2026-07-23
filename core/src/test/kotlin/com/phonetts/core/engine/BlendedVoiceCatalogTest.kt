package com.phonetts.core.engine

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.testing.FakeEngine
import com.phonetts.core.testing.testDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam tests for surfacing saved mixes in the main voice list (issue #42): re-applying persisted
 * [BlendedVoiceSpec]s to a loaded engine and merging the resulting voices into the descriptor's own
 * list. Fail-soft - a non-blendable engine or an unknown source voice contributes nothing.
 */
class BlendedVoiceCatalogTest {
    // A blend-capable engine double: a VoiceEngine (as the real Kokoro/KittenTTS are) that also
    // implements BlendableVoices. It knows a fixed set of source voice ids and mints a new voice per
    // accepted spec (returning null for an unknown source, exactly like the real engines).
    private class FakeBlendEngine(private val known: Set<String>) : VoiceEngine, BlendableVoices {
        override val id = "blend"
        override val displayName = "Blend"

        override fun inspect(bundle: ModelBundle) = null

        override fun forcedMatch(bundle: ModelBundle) = EngineMatch(id, testDescriptor(bundle.id, id))

        override suspend fun load(descriptor: ModelDescriptor) = Unit

        override fun unload() = Unit

        override fun voices(): List<Voice> = known.map { Voice(it, it, "en") }

        override fun synthesize(
            text: String,
            voiceId: String,
            params: SynthesisParams,
        ): Flow<FloatArray> = flowOf(floatArrayOf(0f))

        override fun addBlendedVoice(spec: BlendedVoiceSpec): Voice? {
            if (spec.voiceAId !in known || spec.voiceBId !in known) return null
            return Voice(id = spec.id, name = spec.name, language = "en")
        }
    }

    private fun spec(
        id: String,
        a: String,
        b: String,
    ) = BlendedVoiceSpec(id = id, name = "Mix $id", modelId = "m", voiceAId = a, voiceBId = b, weight = 0.5f)

    @Test
    fun `apply registers each valid spec and skips unknown sources`() {
        val engine = FakeBlendEngine(known = setOf("a", "b"))
        val voices =
            BlendedVoiceCatalog.apply(
                engine,
                listOf(spec("mix1", "a", "b"), spec("mix2", "a", "ghost")),
            )

        assertEquals(listOf("mix1"), voices.map { it.id })
    }

    @Test
    fun `apply yields nothing for a non-blendable engine`() {
        val plain = FakeEngine(id = "e")
        assertEquals(emptyList(), BlendedVoiceCatalog.apply(plain, listOf(spec("mix1", "a", "b"))))
    }

    @Test
    fun `apply yields nothing for a null engine`() {
        assertEquals(emptyList(), BlendedVoiceCatalog.apply(null, listOf(spec("mix1", "a", "b"))))
    }

    @Test
    fun `merge appends blended voices after the descriptor's own`() {
        val base = listOf(Voice("v0", "Voice 0", "en"), Voice("v1", "Voice 1", "en"))
        val blended = listOf(Voice("mix1", "Mix 1", "en"))

        val merged = BlendedVoiceCatalog.merge(base, blended)

        assertEquals(listOf("v0", "v1", "mix1"), merged.map { it.id })
    }

    @Test
    fun `merge never duplicates or shadows a base voice`() {
        val base = listOf(Voice("v0", "Voice 0", "en"))
        val blended = listOf(Voice("v0", "Shadow", "en"), Voice("mix1", "Mix 1", "en"))

        val merged = BlendedVoiceCatalog.merge(base, blended)

        assertEquals(listOf("v0", "mix1"), merged.map { it.id })
        assertEquals("Voice 0", merged.first { it.id == "v0" }.name)
    }
}
