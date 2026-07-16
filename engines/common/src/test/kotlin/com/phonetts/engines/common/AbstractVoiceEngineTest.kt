package com.phonetts.engines.common

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A trivial engine over the base, so the shared synthesize() shape can be tested in isolation. */
private class StubEngine(loaded: Boolean) : AbstractVoiceEngine(
    EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer()),
) {
    override val id: String = "stub"
    override val displayName: String = "Stub"
    override val engineLabel: String = "Stub"

    private var loaded = loaded
    val sentences = mutableListOf<String>()

    override fun isLoaded(): Boolean = loaded

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        speed: Float,
    ): FloatArray {
        sentences.add(sentence)
        return floatArrayOf(0.1f)
    }

    override fun inspect(bundle: ModelBundle): EngineMatch? = null

    override fun forcedMatch(bundle: ModelBundle): EngineMatch = error("not needed")

    override suspend fun load(descriptor: ModelDescriptor) {
        loaded = true
    }

    override fun unload() {
        loaded = false
    }

    override fun voices(): List<Voice> = emptyList()
}

class AbstractVoiceEngineTest {
    @Test
    fun rejectsNonPositiveSpeedEagerly() {
        // Thrown before the Flow is even collected — a guard, not a lazy failure.
        assertFailsWith<IllegalArgumentException> { StubEngine(loaded = true).synthesize("Hi.", "v", 0f) }
    }

    @Test
    fun rejectsSynthesizeBeforeLoadEagerly() {
        assertFailsWith<IllegalStateException> { StubEngine(loaded = false).synthesize("Hi.", "v", 1f) }
    }

    @Test
    fun emitsOneChunkPerSentence() =
        runTest {
            val engine = StubEngine(loaded = true)
            val audio = engine.synthesize("First one. Second one! Third?", "v", 1f).toList()
            assertEquals(3, audio.size)
            assertEquals(listOf("First one.", "Second one!", "Third?"), engine.sentences)
        }

    @Test
    fun emptyTextEmitsNothing() =
        runTest {
            val audio = StubEngine(loaded = true).synthesize("   ", "v", 1f).toList()
            assertEquals(0, audio.size)
        }
}
