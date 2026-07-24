package com.phonetts.engines.pockettts

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the honest-closed contract (class KDoc "STATUS"): loading against a real bundle's
 * descriptor works (every promised asset path is genuinely present), but no audio is ever
 * fabricated - [com.phonetts.engines.pockettts.PocketTtsEngine.synthesizeSentence] always fails
 * closed with a clear message instead.
 */
class PocketTtsSynthesizeInvariantsTest {
    @Test
    fun `load succeeds against a real bundle's descriptor and voices become available`() =
        runTest {
            val engine = PocketTtsEngine(emptyContext())
            val descriptor = engine.inspect(pocketBundle())!!.descriptor

            engine.load(descriptor)

            assertEquals(descriptor.voices.map { it.id }.toSet(), engine.voices().map { it.id }.toSet())
        }

    @Test
    fun `synthesize fails closed with a clear inference-pending message, not fabricated audio`() =
        runTest {
            val engine = PocketTtsEngine(emptyContext())
            val descriptor = engine.inspect(pocketBundle())!!.descriptor
            engine.load(descriptor)

            val error =
                assertFailsWith<IllegalStateException> {
                    engine.synthesize("Hello world.", descriptor.defaultVoiceId, 1.0f).toList()
                }

            assertTrue(error.message!!.contains("not implemented"), "expected an honest 'not implemented' message")
        }

    @Test
    fun `synthesize throws when the engine has not been loaded`() {
        val engine = PocketTtsEngine(emptyContext())

        assertFailsWith<IllegalStateException> { engine.synthesize("hi", "alba", 1.0f) }
    }

    @Test
    fun `unload clears the loaded voice list and re-locks synthesis`() =
        runTest {
            val engine = PocketTtsEngine(emptyContext())
            val descriptor = engine.inspect(pocketBundle())!!.descriptor
            engine.load(descriptor)

            engine.unload()

            assertTrue(engine.voices().isEmpty())
            assertFailsWith<IllegalStateException> { engine.synthesize("hi", descriptor.defaultVoiceId, 1.0f) }
        }

    @Test
    fun `load fails clearly when the descriptor is missing one of its promised asset paths`() =
        runTest {
            val engine = PocketTtsEngine(emptyContext())
            val descriptor = engine.inspect(pocketBundle())!!.descriptor
            val broken = descriptor.copy(assetPaths = descriptor.assetPaths - PocketTtsEngine.MIMI_DECODER_STEM)

            val error = assertFailsWith<IllegalStateException> { engine.load(broken) }

            assertTrue(error.message!!.contains(PocketTtsEngine.MIMI_DECODER_STEM))
        }
}
