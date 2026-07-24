package com.phonetts.engines.supertonic

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import java.text.Normalizer

/**
 * Supertonic's text frontend: characters -> model indices via the bundle's OWN
 * `onnx/unicode_indexer.json` ([indexer]) - no phonemizer, no g2p (spec §5.2; mirrors
 * [com.phonetts.engines.mms.MmsEngine]'s character-level frontend, not the espeak-ng-backed ones).
 *
 * VALIDATED (docs/research/supertonic-facts.md) against `supertonic-py/supertonic/core.py`'s
 * `UnicodeProcessor.__call__`/`_preprocess_text` and the official `supertone-inc/supertonic` Java
 * example's `UnicodeProcessor.call`/`preprocessText` (both downloaded 2026-07-24):
 *  1. Unicode NFKD normalization.
 *  2. If the text doesn't already end in sentence-final punctuation/quote/bracket, append `.` (the
 *     model was trained on sentence-final punctuation; unterminated input reads worse).
 *  3. Wrap the whole thing in a language tag: `<lang>text</lang>` - or `<na>...</na>` for a
 *     [language] outside [SUPPORTED_LANGUAGES], Supertonic 3's own "language-agnostic" fallback
 *     (`AVAILABLE_LANGUAGES`/`UNKNOWN_LANGUAGE` in `supertonic-py/supertonic/config.py`).
 *  4. Map each remaining Basic-Multilingual-Plane code point through [indexer]; a code point
 *     [indexer] has no entry for (returns -1) or that falls outside the BMP the 65536-entry table
 *     covers at all (astral-plane characters, e.g. most emoji) is DROPPED rather than crashing -
 *     the same fail-soft out-of-vocabulary handling
 *     [com.phonetts.engines.mms.MmsFrontend]/[com.phonetts.engines.kittentts.KittenFrontend] use.
 *
 * NOT reproduced here (a text-preprocessing completeness gap, not an identification failure -
 * mirrors the honesty note on [com.phonetts.engines.mms.MmsFrontend]'s `is_uroman`/`phonemize`
 * gap): the reference implementation's explicit emoji-range stripping, dash/quote/bracket
 * normalization, and abbreviation expansion (`e.g.,` -> `for example, `, etc.). None of those
 * change which characters are SUPPORTED - [indexer] already answers that via its own `-1` sentinel
 * for unsupported/decorative symbols - they only smooth cosmetic edge cases in the synthesized
 * reading, which is out of this seam's scope (CLAUDE.md "test the plumbing, not the audio").
 */
internal class SupertonicFrontend(
    private val indexer: IntArray,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val withEnding = if (endsWithSentenceFinalPunctuation(normalized)) normalized else "$normalized."
        val lang = if (language in SUPPORTED_LANGUAGES) language else UNKNOWN_LANGUAGE
        val tagged = "<$lang>$withEnding</$lang>"

        val ids = ArrayList<Long>(tagged.length)
        tagged.codePoints().forEach { codePoint ->
            if (codePoint >= indexer.size) return@forEach
            val index = indexer[codePoint]
            if (index < 0) return@forEach
            ids.add(index.toLong())
        }
        return ModelInput(tokenIds = ids.toLongArray())
    }

    private fun endsWithSentenceFinalPunctuation(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return true
        return trimmed.last() in SENTENCE_FINAL_CHARS
    }

    companion object {
        // VALIDATED (docs/research/supertonic-facts.md): supertonic-py/supertonic/config.py
        // SUPPORTED_LANGUAGES, verbatim order (also the order the "language" ModelParameter's
        // choices are declared in - see SupertonicEngine.LANGUAGE_CHOICES).
        val SUPPORTED_LANGUAGES: List<String> =
            listOf(
                "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi",
                "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv",
                "tr", "uk", "vi",
            )

        // supertonic-py/supertonic/config.py UNKNOWN_LANGUAGE - Supertonic 3's own fallback for
        // text whose language is unknown/unsupported; wraps text as <na>...</na> instead of
        // refusing it.
        const val UNKNOWN_LANGUAGE = "na"

        // supertonic-py/supertonic/core.py _ENDING_PUNCTUATION_PATTERN, ASCII-range subset (the
        // reference regex also covers several CJK closing punctuation marks; see class KDoc's
        // documented gap above).
        private val SENTENCE_FINAL_CHARS = ".!?;:,'\")]}".toSet()
    }
}
