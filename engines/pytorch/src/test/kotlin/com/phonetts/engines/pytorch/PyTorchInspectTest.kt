package com.phonetts.engines.pytorch

import com.phonetts.core.model.ModelBundle
import com.phonetts.engines.common.testing.assertInspectRejects
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the verdict recorded in [PyTorchEngine]'s kdoc and `engines/pytorch/INTEGRATION.md`:
 * `inspect()` fails closed **unconditionally** - there is no on-device PyTorch runtime, so even a
 * bundle that is unmistakably a raw PyTorch TTS checkpoint (weights + a TTS-shaped `config.json`)
 * must never be claimed (CLAUDE.md rule 4). `forcedMatch()` also always throws, which is itself the
 * honest behavior here (every other engine's `forcedMatch` never refuses; this one always does,
 * because honoring a forced pick would hand back a descriptor nothing can ever [load]).
 */
class PyTorchInspectTest {
    private val engine = PyTorchEngine()

    @Test
    fun `inspect rejects a bundle with no PyTorch weight file at all`() {
        val bundle = ModelBundle(id = "unrelated", fileNames = setOf("readme.txt"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect rejects a bundle that looks exactly like a real raw PyTorch TTS checkpoint`() {
        // Modeled on the real myshell-ai/MeloTTS-English shape (checkpoint.pth + config.json) -
        // even this unmistakable case must return null (see class kdoc: recognizing the shape is
        // not the same as being able to run it).
        val bundle =
            ModelBundle(
                id = "melotts-english-checkpoint",
                fileNames = setOf("checkpoint.pth", "config.json"),
                sideFiles = mapOf("config.json" to """{"data":{"sampling_rate":44100},"symbols":["_","a"]}"""),
            )
        assertTrue(RawPyTorchBundle.looksLikeRawPyTorch(bundle.fileNames), "test bundle should look like raw pytorch")

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect rejects an onnx bundle too (this engine claims nothing, ever)`() {
        val bundle = ModelBundle(id = "piper-voice", fileNames = setOf("en_US-voice.onnx", "en_US-voice.onnx.json"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch always throws, even for a well-formed raw PyTorch checkpoint`() {
        val bundle = ModelBundle(id = "manual", fileNames = setOf("checkpoint.pth", "config.json"))

        val error = assertFailsWith<UnsupportedOperationException> { engine.forcedMatch(bundle) }

        assertTrue(error.message!!.contains("looks like a raw PyTorch checkpoint"), "message was: ${error.message}")
    }

    @Test
    fun `forcedMatch throws a different, honest message for a bundle that doesn't even look like PyTorch`() {
        val bundle = ModelBundle(id = "unusable", fileNames = setOf("readme.txt"))

        val error = assertFailsWith<UnsupportedOperationException> { engine.forcedMatch(bundle) }

        assertTrue(
            error.message!!.contains("does not even look like a raw PyTorch checkpoint"),
            "message was: ${error.message}",
        )
    }

    @Test
    fun `voices are always empty since no model can ever be loaded`() {
        assertTrue(engine.voices().isEmpty())
    }
}
