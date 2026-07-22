package com.phonetts.core.prefs

import com.phonetts.core.engine.BlendedVoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Seam tests for saved-voice-mix persistence (issue #42): round-trip, per-model scoping, replace, remove. */
class BlendedVoiceStoreTest {
    private fun spec(
        id: String,
        modelId: String,
        weight: Float = 0.5f,
    ) = BlendedVoiceSpec(
        id = id,
        name = "Mix $id",
        modelId = modelId,
        voiceAId = "af_heart",
        voiceBId = "af_bella",
        weight = weight,
    )

    @Test
    fun `a saved mix round-trips with all fields intact`() {
        val store = BlendedVoiceStore(InMemoryPreferenceStore())
        val saved = spec("mix1", "kokoro", weight = 0.3f)
        store.save(saved)

        val loaded = store.forModel("kokoro")
        assertEquals(listOf(saved), loaded)
    }

    @Test
    fun `mixes are scoped per model`() {
        val store = BlendedVoiceStore(InMemoryPreferenceStore())
        store.save(spec("mix1", "kokoro"))
        store.save(spec("mix2", "kittentts"))

        assertEquals(listOf("mix1"), store.forModel("kokoro").map { it.id })
        assertEquals(listOf("mix2"), store.forModel("kittentts").map { it.id })
    }

    @Test
    fun `saving the same id replaces rather than duplicates`() {
        val store = BlendedVoiceStore(InMemoryPreferenceStore())
        store.save(spec("mix1", "kokoro", weight = 0.2f))
        store.save(spec("mix1", "kokoro", weight = 0.8f))

        val loaded = store.forModel("kokoro")
        assertEquals(1, loaded.size)
        assertEquals(0.8f, loaded.single().weight)
    }

    @Test
    fun `remove drops only the named mix`() {
        val store = BlendedVoiceStore(InMemoryPreferenceStore())
        store.save(spec("mix1", "kokoro"))
        store.save(spec("mix2", "kokoro"))

        store.remove("kokoro", "mix1")

        assertEquals(listOf("mix2"), store.forModel("kokoro").map { it.id })
    }

    @Test
    fun `an unknown model has no mixes`() {
        val store = BlendedVoiceStore(InMemoryPreferenceStore())
        assertTrue(store.forModel("nope").isEmpty())
    }

    @Test
    fun `a name containing spaces and punctuation round-trips`() {
        val store = BlendedVoiceStore(InMemoryPreferenceStore())
        val saved = spec("mix1", "kokoro").copy(name = "Heart + Bella (60%)")
        store.save(saved)
        assertEquals("Heart + Bella (60%)", store.forModel("kokoro").single().name)
    }
}
