package com.phonetts.engines.melotts

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.text.Phonemizer
import com.phonetts.engines.common.singleTensorOrError

/**
 * MeloTTS's own [TextFrontend] (spec §5.2, Phase 2.2), rebuilt against the REAL acoustic-model
 * contract validated in docs/research/onnx-io.md (the MeloTTS section). Produces the acoustic
 * model's `x` (phoneme ids, carried as [ModelInput.tokenIds]), `tone`, `language`, `bert`, and
 * `ja_bert` inputs for ENGLISH text.
 *
 * Two real-MeloTTS facts drive the shape of this class (from `melo/text/{symbols,english}.py` and
 * `melo/utils.py::get_text_for_tts_infer`, upstream myshell-ai/MeloTTS, checked 2026-07):
 *
 *  1. **One shared multilingual symbol/tone/language table** ([MeloSymbolTable]), not a
 *     per-language one — English phonemes occupy specific rows the real model was trained with, so
 *     that table is copied verbatim rather than invented (see its KDoc).
 *  2. **`bert` (1024-dim) is ZEROED for every non-Chinese language in real MeloTTS** — only
 *     `ja_bert` (768-dim) carries the actual BERT hidden states for English/Japanese/etc. That is
 *     not a shortcut this engine takes; it is what `get_text_for_tts_infer` itself does
 *     (`bert = zeros(1024, len(phone))` in the `EN` branch). This frontend reproduces that exactly.
 *
 * What is genuinely approximated (documented, not silently guessed):
 *  - **G2P**: real MeloTTS uses `g2p_en` (CMUdict + neural fallback) to emit ARPAbet. This module
 *    has neither in Kotlin, so it routes through the shared espeak-ng-backed [phonemizer] (spec
 *    §5.2's shared path) and maps IPA codepoints to the closest [MeloSymbolTable] English symbol
 *    one at a time via [MeloEnglishPhonemeMap]. Valid, in-vocabulary ids every time; not
 *    upstream-identical pronunciation.
 *  - **BERT alignment**: real MeloTTS expands `bert-base-uncased` hidden states to match the
 *    phoneme sequence via an exact `word2ph` (word -> phoneme count) map from its own tokenizer.
 *    This frontend does not have that alignment, so [resampleToPhoneCount] nearest-neighbour
 *    resamples the BERT run's `[1, L, 768]` output along the sequence axis to the acoustic model's
 *    phoneme count instead — real BERT signal reaches `ja_bert`, but not word-aligned.
 *  - **BERT tokenization**: [tokenizeForBert] is the same placeholder hash tokenizer the previous
 *    version of this file used (real MeloTTS loads `bert-base-uncased`'s WordPiece vocab, which
 *    ships with the model bundle, not this jar) — swap for a real vocab loaded from
 *    `descriptor.assetPaths[TOKENIZER_ASSET]` when available.
 */
class MeloFrontend(
    private val bertSession: InferenceSession,
    private val phonemizer: Phonemizer,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val core = buildCoreSequence(phonemizer.phonemize(text, language))
        val phoneIds = intersperse(core.ids, BLANK_VALUE)
        val tones = intersperse(core.tones, BLANK_VALUE)
        val languages = intersperse(LongArray(core.ids.size) { MeloSymbolTable.EN_LANGUAGE_ID.toLong() }, BLANK_VALUE)
        val tokenCount = phoneIds.size

        val bertOutput = runBert(tokenizeForBert(text))
        val jaBert = resampleToPhoneCount(bertOutput, JA_BERT_DIM, tokenCount)
        val bertZeros = Tensor.floats(FloatArray(BERT_DIM * tokenCount), intArrayOf(1, BERT_DIM, tokenCount))

        return ModelInput(
            tokenIds = phoneIds,
            extras =
                mapOf(
                    EXTRA_TONE to tones,
                    EXTRA_LANGUAGE to languages,
                    EXTRA_BERT to bertZeros,
                    EXTRA_JA_BERT to jaBert,
                ),
        )
    }

    /** BOS "_" + one entry per IPA codepoint (phonemes, `SP` word breaks, stress markers consumed) + EOS "_". */
    private fun buildCoreSequence(ipa: String): CoreSequence {
        val ids = mutableListOf<Long>()
        val tones = mutableListOf<Long>()
        appendUnit(ids, tones, MeloSymbolTable.PAD_ID, CONSONANT_TONE)
        var pendingVowelTone = UNSTRESSED_VOWEL_TONE
        for (ch in ipa) {
            pendingVowelTone = appendIpaChar(ch, ids, tones, pendingVowelTone)
        }
        appendUnit(ids, tones, MeloSymbolTable.PAD_ID, CONSONANT_TONE)
        return CoreSequence(ids.toLongArray(), tones.toLongArray())
    }

    /** Returns the (possibly updated) pending stress class for the NEXT vowel this word emits. */
    private fun appendIpaChar(
        ch: Char,
        ids: MutableList<Long>,
        tones: MutableList<Long>,
        pendingVowelTone: Long,
    ): Long =
        when {
            ch == MeloEnglishPhonemeMap.PRIMARY_STRESS_MARK -> PRIMARY_STRESS_TONE
            ch == MeloEnglishPhonemeMap.SECONDARY_STRESS_MARK -> SECONDARY_STRESS_TONE
            ch == MeloEnglishPhonemeMap.LENGTH_MARK -> pendingVowelTone
            ch.isWhitespace() -> {
                appendUnit(ids, tones, MeloSymbolTable.SP_ID, CONSONANT_TONE)
                UNSTRESSED_VOWEL_TONE
            }
            MeloEnglishPhonemeMap.isVowel(ch) -> {
                appendUnit(ids, tones, MeloSymbolTable.idFor(MeloEnglishPhonemeMap.symbolFor(ch)), pendingVowelTone)
                UNSTRESSED_VOWEL_TONE
            }
            else -> {
                appendUnit(ids, tones, MeloSymbolTable.idFor(MeloEnglishPhonemeMap.symbolFor(ch)), CONSONANT_TONE)
                pendingVowelTone
            }
        }

    private fun appendUnit(
        ids: MutableList<Long>,
        tones: MutableList<Long>,
        symbolId: Int,
        localTone: Long,
    ) {
        ids += symbolId.toLong()
        tones += MeloSymbolTable.EN_TONE_OFFSET + localTone
    }

    /** `commons.intersperse` upstream: `[pad, v0, pad, v1, pad, ..., vN, pad]` — length `2N+1`. */
    private fun intersperse(
        values: LongArray,
        pad: Long,
    ): LongArray {
        val result = LongArray(values.size * 2 + 1) { pad }
        for (index in values.indices) {
            result[index * 2 + 1] = values[index]
        }
        return result
    }

    private fun runBert(tokenIds: LongArray): Tensor {
        val shape = intArrayOf(1, tokenIds.size)
        val zeros = LongArray(tokenIds.size)
        val ones = LongArray(tokenIds.size) { 1L }
        val outputs =
            bertSession.run(
                mapOf(
                    BERT_INPUT_IDS to Tensor.longs(tokenIds, shape),
                    BERT_TOKEN_TYPE_IDS to Tensor.longs(zeros, shape),
                    BERT_ATTENTION_MASK to Tensor.longs(ones, shape),
                ),
            )
        return outputs.singleTensorOrError(ENGINE_LABEL)
    }

    /** Nearest-neighbour resample of a `[1, L, hiddenDim]` BERT run to `[1, hiddenDim, targetLen]` (see class KDoc). */
    private fun resampleToPhoneCount(
        bertOutput: Tensor,
        hiddenDim: Int,
        targetLen: Int,
    ): Tensor {
        val flat = bertOutput.asFloats()
        val sourceLen = flat.size / hiddenDim
        val result = FloatArray(hiddenDim * targetLen)
        if (sourceLen > 0) {
            fillResampled(result, flat, hiddenDim, sourceLen, targetLen)
        }
        return Tensor.floats(result, intArrayOf(1, hiddenDim, targetLen))
    }

    private fun fillResampled(
        result: FloatArray,
        source: FloatArray,
        hiddenDim: Int,
        sourceLen: Int,
        targetLen: Int,
    ) {
        for (t in 0 until targetLen) {
            val sourceIndex = ((t * sourceLen) / targetLen.coerceAtLeast(1)).coerceIn(0, sourceLen - 1)
            for (h in 0 until hiddenDim) {
                result[h * targetLen + t] = source[sourceIndex * hiddenDim + h]
            }
        }
    }

    /**
     * Placeholder standing in for MeloTTS's real `bert-base-uncased` WordPiece tokenizer (which
     * would be loaded from `descriptor.assetPaths[TOKENIZER_ASSET]`). Splits on whitespace and
     * hashes each word into a fixed vocab range, framed by BOS/EOS ids — enough to exercise the
     * token-id -> BERT -> resample wiring deterministically in tests.
     */
    private fun tokenizeForBert(text: String): LongArray {
        val words = text.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val ids = LongArray(words.size + 2)
        ids[0] = BERT_BOS_ID
        words.forEachIndexed { index, word -> ids[index + 1] = wordId(word) }
        ids[ids.size - 1] = BERT_EOS_ID
        return ids
    }

    private fun wordId(word: String): Long = (word.hashCode().toLong() and MASK_31_BITS) % VOCAB_SIZE + FIRST_TOKEN_ID

    private data class CoreSequence(val ids: LongArray, val tones: LongArray)

    companion object {
        const val EXTRA_TONE = "tone"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_BERT = "bert"
        const val EXTRA_JA_BERT = "ja_bert"

        /** zh BERT feature width the acoustic model's `bert` input expects (always zeroed here, see class KDoc). */
        const val BERT_DIM = 1024

        /** ja/other-language BERT feature width — the acoustic model's `ja_bert` input, and the BERT run's output. */
        const val JA_BERT_DIM = 768

        private const val BERT_INPUT_IDS = "input_ids"
        private const val BERT_TOKEN_TYPE_IDS = "token_type_ids"
        private const val BERT_ATTENTION_MASK = "attention_mask"
        private const val ENGINE_LABEL = "MeloFrontend"

        private const val BLANK_VALUE = 0L
        private const val CONSONANT_TONE = 0L
        private const val UNSTRESSED_VOWEL_TONE = 1L
        private const val PRIMARY_STRESS_TONE = 2L
        private const val SECONDARY_STRESS_TONE = 3L

        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val BERT_BOS_ID = 0L
        private const val BERT_EOS_ID = 1L
        private const val FIRST_TOKEN_ID = 2L
        private const val VOCAB_SIZE = 30_000L
        private const val MASK_31_BITS = 0x7fffffffL
    }
}
