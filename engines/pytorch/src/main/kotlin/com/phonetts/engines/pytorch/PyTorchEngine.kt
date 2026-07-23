package com.phonetts.engines.pytorch

import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * A deliberately inert placeholder for raw-PyTorch-checkpoint models (the concrete target
 * considered was `myshell-ai/MeloTTS-English`: a `checkpoint.pth` + `config.json`).
 *
 * VERDICT (recorded here and in `engines/pytorch/INTEGRATION.md`, after a dedicated on-device
 * feasibility check): **raw PyTorch cannot run on this app's target devices.** Two independent
 * blockers, either one fatal on its own:
 *  1. Android's W^X / SELinux policy (enforced from `targetSdk` 29 onward, regardless of
 *     sideloading or root) refuses to execute or `dlopen()` code a non-privileged app downloads
 *     into its own storage at runtime - so "download a Python interpreter on demand" (the design
 *     this module started with) cannot work AT ALL, not just poorly.
 *  2. Even ignoring (1), PyTorch publishes no `arm64-Android` wheels - `manylinux` wheels are
 *     glibc-linked and Android is Bionic libc - and Chaquopy's unofficial community `torch` build
 *     is documented as broken. There is no working torch runtime to provision even in principle.
 *
 * So this engine's [inspect] **always** returns null - not a heuristic that sometimes fails, a
 * hard, permanent "no" (CLAUDE.md rule 4: fail closed). It exists at all only so the registry has
 * a documented, harmless placeholder for this model family rather than silence; deleting this
 * module removes it with no other change (CLAUDE.md rule 5), exactly like every other engine.
 *
 * The actually-supported path for a model shaped like this is **converting it**, offline, before
 * it ever reaches this app: to ONNX (the community has already done this for MeloTTS via
 * `k2-fsa/sherpa-onnx`, which `:engines:melotts` in this repo already targets) or to ExecuTorch
 * (`:engines:executorch`). [RawPyTorchBundle.looksLikeRawPyTorch] is the pure, tested helper a
 * future call site can use to recognize that shape and say so clearly, instead of a bare "no
 * engine recognized this model".
 */
internal class PyTorchEngine : VoiceEngine {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME

    /**
     * Always null (see class kdoc): there is no on-device runtime this engine could hand a match
     * to, so recognizing the bundle's *shape* would only be a lie about runnability. Deliberately
     * does NOT consult [RawPyTorchBundle.looksLikeRawPyTorch] to decide - that helper answers "does
     * this look like a raw PyTorch checkpoint", a different question from "can I run it", and
     * conflating the two here is exactly the mistake rule 4 exists to prevent.
     */
    override fun inspect(bundle: ModelBundle): EngineMatch? = null

    /**
     * Always throws (see class kdoc) - there is no runtime to load a match against, so honoring a
     * forced assignment would hand back a descriptor this engine can never actually [load]. Unlike
     * every other engine's `forcedMatch` (which fills in family defaults for the user's explicit
     * pick), refusing here is the honest behavior: [RawPyTorchBundle.looksLikeRawPyTorch] is
     * consulted only to make the message more specific, never to change the outcome.
     */
    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val shapeNote =
            if (RawPyTorchBundle.looksLikeRawPyTorch(bundle.fileNames)) {
                "'${bundle.id}' looks like a raw PyTorch checkpoint (weights + config.json)"
            } else {
                "'${bundle.id}' does not even look like a raw PyTorch checkpoint"
            }
        throw UnsupportedOperationException(
            "$shapeNote, but PhoneTTS has no on-device PyTorch runtime (no arm64-Android torch build," +
                " and Android blocks executing downloaded code - see engines/pytorch/INTEGRATION.md)." +
                " Convert it to ONNX (see :engines:melotts / k2-fsa/sherpa-onnx) or ExecuTorch" +
                " (:engines:executorch) before using it with PhoneTTS.",
        )
    }

    /** Unreachable in practice ([inspect] never matches and [forcedMatch] never returns), kept only
     * to satisfy [VoiceEngine]'s contract defensively rather than silently no-op. */
    override suspend fun load(descriptor: ModelDescriptor): Unit =
        throw UnsupportedOperationException(NO_RUNTIME_MESSAGE)

    override fun unload() = Unit

    /** No model is ever loaded (see class kdoc) - honest empty list, never a fabricated default voice. */
    override fun voices(): List<Voice> = emptyList()

    override fun synthesize(
        text: String,
        voiceId: String,
        params: SynthesisParams,
    ): Flow<FloatArray> = throw UnsupportedOperationException(NO_RUNTIME_MESSAGE)

    companion object {
        const val ENGINE_ID = "pytorch"
        private const val DISPLAY_NAME = "PyTorch (raw checkpoint) - unsupported on-device"

        private const val NO_RUNTIME_MESSAGE =
            "PyTorchEngine has no on-device runtime to load/synthesize with - see engines/pytorch/INTEGRATION.md"
    }
}
