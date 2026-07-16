package com.phonetts.engines.melotts

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.tensorOrError

/**
 * MeloTTS's own [TextFrontend] (spec §5.2, Phase 2.2). This is the reason MeloTTS is in the
 * build order early: unlike the phoneme-only engines, MeloTTS conditions its acoustic model on
 * learned BERT linguistic features, not just a phoneme/token id sequence. So this frontend does
 * two things a plain phonemizer-backed frontend never does:
 *
 *  1. Runs its own language-specific tokenizer (real MeloTTS: jieba for Chinese, a
 *     bert-base-uncased/bert-base-multilingual-uncased wordpiece vocab otherwise) to get token
 *     ids for BOTH the BERT step and the acoustic model.
 *  2. Feeds those token ids through a SECOND [InferenceSession] — the BERT prosody model — and
 *     carries its hidden-state output forward as [ModelInput.extras], for the acoustic model to
 *     consume as a prosody-conditioning input.
 *
 * The BERT session is owned by [MeloEngine] (loaded from `descriptor.assetPaths[BERT_ASSET]`)
 * and handed in here; this class never touches the runtime registry directly, keeping it a
 * plain, unit-testable seam per a fake session.
 */
class MeloFrontend(
    private val bertSession: InferenceSession,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val tokenIds = tokenize(text)
        val attentionMask = LongArray(tokenIds.size) { 1L }
        val bertOutputs =
            bertSession.run(
                mapOf(
                    // Real MeloTTS BERT ONNX export input names.
                    BERT_INPUT_IDS to Tensor.longs(tokenIds, intArrayOf(1, tokenIds.size)),
                    BERT_ATTENTION_MASK to Tensor.longs(attentionMask, intArrayOf(1, tokenIds.size)),
                ),
            )
        val bertFeatures = bertOutputs.tensorOrError(BERT_OUTPUT_FEATURES, ENGINE_LABEL)

        return ModelInput(
            tokenIds = tokenIds,
            extras = mapOf(EXTRA_BERT_FEATURES to bertFeatures),
        )
    }

    /**
     * Minimal placeholder tokenizer standing in for MeloTTS's real language-specific vocab
     * (which would be loaded from `descriptor.assetPaths[TOKENIZER_ASSET]`). Splits on
     * whitespace and hashes each word into a fixed vocab range, framed by BOS/EOS ids — enough
     * to exercise the token-id -> BERT -> acoustic wiring deterministically in tests.
     */
    private fun tokenize(text: String): LongArray {
        val words = text.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val ids = LongArray(words.size + 2)
        ids[0] = BOS_ID
        words.forEachIndexed { index, word -> ids[index + 1] = wordId(word) }
        ids[ids.size - 1] = EOS_ID
        return ids
    }

    private fun wordId(word: String): Long = (word.hashCode().toLong() and MASK_31_BITS) % VOCAB_SIZE + FIRST_TOKEN_ID

    companion object {
        const val EXTRA_BERT_FEATURES = "bert_features"

        private const val BERT_INPUT_IDS = "input_ids"
        private const val BERT_ATTENTION_MASK = "attention_mask"
        private const val BERT_OUTPUT_FEATURES = "bert_features"
        private const val ENGINE_LABEL = "MeloFrontend"

        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val BOS_ID = 0L
        private const val EOS_ID = 1L
        private const val FIRST_TOKEN_ID = 2L
        private const val VOCAB_SIZE = 30_000L
        private const val MASK_31_BITS = 0x7fffffffL
    }
}
