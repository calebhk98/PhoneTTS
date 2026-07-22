package com.phonetts.engines.mms

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend

/**
 * MMS's text frontend: **character/grapheme -> id**, via the model's OWN [vocab] (read from that
 * bundle's `vocab.json` at load — never a shared/global table, spec §5.2/CLAUDE.md rule 1). This
 * is deliberately NOT espeak-based (unlike Piper/KittenTTS/Kokoro): MMS-VITS's tokenizer maps raw
 * text characters straight to ids with no phoneme step, so [com.phonetts.core.engine.EngineContext.phonemizer]
 * is unused here.
 *
 * Mirrors the exact preprocessing pipeline baked into every real `Xenova/mms-tts-*`
 * `tokenizer.json`'s Rust-tokenizers `normalizer` (VALIDATED by reading `Xenova/mms-tts-eng` and
 * `Xenova/mms-tts-ara`'s `tokenizer.json`/`tokenizer_config.json` directly, 2026-07-22):
 *  1. Lowercase the text.
 *  2. Drop every character absent from [vocab] (the tokenizer's normalizer replaces them with the
 *     empty string outright — never mapped to an "unknown" id, so `unk_token` is never actually
 *     produced by this path and is intentionally not modelled here).
 *  3. Trim leading/trailing whitespace from what remains.
 *  4. If [addBlank] (true for both bundles checked; read from `tokenizer_config.json`, never
 *     assumed): intersperse [padId] before every kept character AND once more at the end —
 *     `pad, c1, pad, c2, pad, ..., cN, pad` (length `2N+1`). This is the MMS/VITS equivalent of
 *     Piper's BOS/PAD/EOS framing, except a single id plays every role (`vocab.json`'s id `0` is
 *     always both a real grapheme AND the blank/pad token in every bundle checked).
 */
internal class MmsFrontend(
    private val vocab: Map<String, Long>,
    private val padId: Long,
    private val addBlank: Boolean,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val kept = text.lowercase().filter { ch -> vocab.containsKey(ch.toString()) }.trim()
        val ids = if (addBlank) interspersed(kept) else kept.map { ch -> vocab.getValue(ch.toString()) }
        return ModelInput(tokenIds = ids.toLongArray())
    }

    private fun interspersed(kept: CharSequence): List<Long> {
        val ids = mutableListOf(padId)
        for (ch in kept) {
            ids.add(vocab.getValue(ch.toString()))
            ids.add(padId)
        }
        return ids
    }
}
