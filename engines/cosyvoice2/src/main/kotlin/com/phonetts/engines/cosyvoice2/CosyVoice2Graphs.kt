package com.phonetts.engines.cosyvoice2

import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.floatsOrError
import com.phonetts.engines.common.tensorOrError

/**
 * The two DETERMINISTIC, ONNX halves of the CosyVoice2 pipeline — flow-matching decoder
 * (speech tokens -> mel) and HiFT vocoder (mel -> waveform) — kept out of
 * [CosyVoice2Engine] so that class stays small (detekt LargeClass) and the autoregressive LLM
 * stage (the non-ONNX [com.phonetts.core.runtime.SpeechTokenRuntime]) reads clearly against them.
 *
 * REAL tensor names, inspected on 2026-07-16 with `onnx.load(...).graph` over the published
 * exports (the model files are NOT committed — CLAUDE.md):
 *
 *  - Flow: `Lourdle/CosyVoice2-0.5B_ONNX/flow_fp32.onnx`
 *      inputs : token INT64[B,L], prompt_token INT32[B,PL], prompt_feat FLOAT[B,ML,80],
 *               embedding FLOAT[B,E]
 *      output : tts_mel FLOAT[B,80,mel_len]
 *  - HiFT: `Lourdle/CosyVoice2-0.5B_ONNX/hift.onnx`
 *      input  : speech_feat FLOAT[1,80,L]
 *      output : generated_speech FLOAT[1,N]
 *
 * (The same repo's `flow_hift_combined_fp32.onnx` fuses both and adds a scalar `speed` FLOAT[]
 * input — evidence that speed is a native flow-side token-rate knob, which is why this engine
 * routes speed to the token stage and never resamples audio, CLAUDE.md rule 2.)
 *
 * KNOWN RUNTIME GAP: the flow graph's `prompt_token` is INT32, but the app's [Tensor]/OnnxRuntime
 * seam only carries INT64/FLOAT, so it is fed as INT64 here. Feeding the real graph correctly
 * needs an INT32 path in OnnxRuntime — a documented native TODO (docs/COSYVOICE2.md), invisible to
 * these plumbing tests which drive fake sessions.
 */
internal object CosyVoice2Graphs {
    const val MEL_DIM = 80

    // Flow-matching decoder (tokens -> mel).
    const val FLOW_INPUT_TOKEN = "token"
    const val FLOW_INPUT_PROMPT_TOKEN = "prompt_token"
    const val FLOW_INPUT_PROMPT_FEAT = "prompt_feat"
    const val FLOW_INPUT_EMBEDDING = "embedding"
    const val FLOW_OUTPUT_MEL = "tts_mel"

    // HiFT vocoder (mel -> waveform @ 24000 Hz).
    const val HIFT_INPUT_MEL = "speech_feat"
    const val HIFT_OUTPUT_AUDIO = "generated_speech"

    /** Run the flow-matching decoder: LLM speech [tokens] + the baked voice -> mel tensor. */
    fun decodeFlow(
        session: InferenceSession,
        tokens: LongArray,
        prompt: SpeakerPrompt,
        engineLabel: String,
    ): Tensor {
        val inputs =
            mapOf(
                FLOW_INPUT_TOKEN to Tensor.longs(tokens, intArrayOf(1, tokens.size)),
                FLOW_INPUT_PROMPT_TOKEN to Tensor.longs(prompt.promptTokens, intArrayOf(1, prompt.promptTokens.size)),
                FLOW_INPUT_PROMPT_FEAT to
                    Tensor.floats(prompt.promptFeat, intArrayOf(1, prompt.promptFeatFrames, MEL_DIM)),
                FLOW_INPUT_EMBEDDING to Tensor.floats(prompt.embedding, intArrayOf(1, prompt.embedding.size)),
            )
        return session.run(inputs).tensorOrError(FLOW_OUTPUT_MEL, engineLabel)
    }

    /** Run the HiFT vocoder: [mel] tensor (already shaped [1,80,L]) -> raw waveform samples. */
    fun vocode(
        session: InferenceSession,
        mel: Tensor,
        engineLabel: String,
    ): FloatArray = session.run(mapOf(HIFT_INPUT_MEL to mel)).floatsOrError(HIFT_OUTPUT_AUDIO, engineLabel)
}
