package com.phonetts.engines.common

import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.testing.testDescriptor
import com.phonetts.engines.common.testing.engineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A trivial engine over the base, so the shared load()/synthesize() template methods can be
 * tested in isolation. [runtimeAvailable] stands in for a real runtime's `isAvailable()` (issue
 * #18 item 3) without needing an actual [com.phonetts.core.runtime.Runtime].
 */
private class StubEngine(
    private val runtimeAvailable: Boolean = true,
) : AbstractVoiceEngine(engineContext()) {
    override val id: String = "stub"
    override val displayName: String = "Stub"
    override val engineLabel: String = "Stub"

    private var loaded = false
    val sentences = mutableListOf<String>()
    var doLoadCount = 0
        private set

    override fun isLoaded(): Boolean = loaded

    override fun isRuntimeAvailable(): Boolean = runtimeAvailable

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        sentences.add(sentence)
        return floatArrayOf(0.1f)
    }

    override fun inspect(bundle: ModelBundle): EngineMatch? = null

    override fun forcedMatch(bundle: ModelBundle): EngineMatch = error("not needed")

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        doLoadCount++
        loaded = true
    }

    override fun unload() {
        loaded = false
    }

    override fun voices(): List<Voice> = emptyList()
}

class AbstractVoiceEngineTest {
    private fun descriptor(speedRange: ClosedFloatingPointRange<Float> = 0.5f..2.0f): ModelDescriptor =
        testDescriptor(modelId = "stub-model", engineId = "stub", speedRange = speedRange)

    @Test
    fun rejectsNonPositiveSpeedEagerly() =
        runTest {
            val engine = StubEngine()
            engine.load(descriptor())

            // Thrown before the Flow is even collected — a guard, not a lazy failure.
            assertFailsWith<IllegalArgumentException> { engine.synthesize("Hi.", "v0", 0f) }
        }

    @Test
    fun rejectsSynthesizeBeforeLoadEagerly() {
        assertFailsWith<IllegalStateException> { StubEngine().synthesize("Hi.", "v0", 1f) }
    }

    @Test
    fun emitsOneChunkPerSentence() =
        runTest {
            val engine = StubEngine()
            engine.load(descriptor())

            val audio = engine.synthesize("First one. Second one! Third?", "v0", 1f).toList()
            assertEquals(3, audio.size)
            assertEquals(listOf("First one.", "Second one!", "Third?"), engine.sentences)
        }

    @Test
    fun emptyTextEmitsNothing() =
        runTest {
            val engine = StubEngine()
            engine.load(descriptor())

            val audio = engine.synthesize("   ", "v0", 1f).toList()
            assertEquals(0, audio.size)
        }

    @Test
    fun rejectsSpeedAboveTheDescriptorsOwnRange() =
        runTest {
            val engine = StubEngine()
            engine.load(descriptor(speedRange = 0.5f..2.0f))

            val error = assertFailsWith<IllegalArgumentException> { engine.synthesize("Hi.", "v0", 3.0f) }
            assertTrue(error.message!!.contains("3.0"), "error should name the offending speed")
        }

    @Test
    fun rejectsSpeedBelowTheDescriptorsOwnRange() =
        runTest {
            val engine = StubEngine()
            engine.load(descriptor(speedRange = 0.5f..2.0f))

            assertFailsWith<IllegalArgumentException> { engine.synthesize("Hi.", "v0", 0.1f) }
        }

    @Test
    fun acceptsSpeedAtTheDescriptorsRangeBoundaries() =
        runTest {
            val engine = StubEngine()
            engine.load(descriptor(speedRange = 0.5f..2.0f))

            engine.synthesize("Hi.", "v0", 0.5f).toList()
            engine.synthesize("Hi.", "v0", 2.0f).toList()
        }

    @Test
    fun aDifferentLoadedDescriptorsRangeIsWhatGetsEnforced() =
        runTest {
            // SSOT (spec rule 1): the range is read from the descriptor actually passed to load(),
            // never a literal baked into the base class.
            val engine = StubEngine()
            engine.load(descriptor(speedRange = 1.0f..1.0f))

            assertFailsWith<IllegalArgumentException> { engine.synthesize("Hi.", "v0", 1.5f) }
        }

    @Test
    fun loadFailsFastThroughTheParentTemplateMethodWhenTheRuntimeIsUnavailable() =
        runTest {
            val engine = StubEngine(runtimeAvailable = false)

            assertFailsWith<IllegalStateException> { engine.load(descriptor()) }
            assertEquals(0, engine.doLoadCount, "doLoad must never run when the runtime is unavailable")
        }

    @Test
    fun loadRunsDoLoadWhenTheRuntimeIsAvailable() =
        runTest {
            val engine = StubEngine(runtimeAvailable = true)

            engine.load(descriptor())

            assertEquals(1, engine.doLoadCount)
        }
}
