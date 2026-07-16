package com.phonetts.engines.melotts

/**
 * MeloTTS's real, trained phoneme/tone/language vocabulary (spec §5.2 — "MeloTTS -> its own
 * frontend"). The acoustic ONNX graph's `x`/`tone`/`language` embedding tables were trained
 * against these EXACT id assignments, so this is copied verbatim (index-for-index) from the
 * upstream `melo/text/symbols.py` (myshell-ai/MeloTTS, fetched 2026-07 to validate
 * docs/research/onnx-io.md) rather than reinvented — a shifted-by-one table would silently
 * point every phoneme at the wrong embedding row.
 *
 * [SYMBOLS] is `pad + sorted(set(zh_symbols + ja_symbols + en_symbols + kr_symbols + es_symbols +
 * fr_symbols + de_symbols + ru_symbols)) + pu_symbols` from the upstream file — MeloTTS is one
 * multilingual model with ONE shared symbol table across all its languages, not one table per
 * language. This module only drives the ENGLISH slice of it (id 0 pad,`en_symbols` names, `SP`
 * for word breaks, `UNK` for anything unmapped), but the OTHER languages' symbols still occupy
 * real slots in the table and must stay present for the English ids to land on the right rows.
 */
internal object MeloSymbolTable {
    val SYMBOLS: List<String> =
        listOf(
            "_", "\"", "(", ")", "*", "/",
            ":", "AA", "E", "EE", "En", "N",
            "OO", "Q", "V", "[", "\\", "]",
            "^", "a", "a:", "aa", "ae", "ah",
            "ai", "an", "ang", "ao", "aw", "ay",
            "b", "by", "c", "ch", "d", "dh",
            "dy", "e", "e:", "eh", "ei", "en",
            "eng", "er", "ey", "f", "g", "gy",
            "h", "hh", "hy", "i", "i0", "i:",
            "ia", "ian", "iang", "iao", "ie", "ih",
            "in", "ing", "iong", "ir", "iu", "iy",
            "j", "jh", "k", "ky", "l", "m",
            "my", "n", "ng", "ny", "o", "o:",
            "ong", "ou", "ow", "oy", "p", "py",
            "q", "r", "ry", "s", "sh", "t",
            "th", "ts", "ty", "u", "u:", "ua",
            "uai", "uan", "uang", "uh", "ui", "un",
            "uo", "uw", "v", "van", "ve", "vn",
            "w", "x", "y", "z", "zh", "zy",
            "~", "æ", "ç", "ð", "ø", "ŋ",
            "œ", "ɐ", "ɑ", "ɒ", "ɔ", "ɕ",
            "ə", "ɛ", "ɜ", "ɡ", "ɣ", "ɥ",
            "ɦ", "ɪ", "ɫ", "ɬ", "ɭ", "ɯ",
            "ɲ", "ɵ", "ɸ", "ɹ", "ɾ", "ʁ",
            "ʃ", "ʊ", "ʌ", "ʎ", "ʏ", "ʑ",
            "ʒ", "ʝ", "ʲ", "ˈ", "ˌ", "ː",
            "̃", "̩", "β", "θ", "ᄀ", "ᄁ",
            "ᄂ", "ᄃ", "ᄄ", "ᄅ", "ᄆ", "ᄇ",
            "ᄈ", "ᄉ", "ᄊ", "ᄋ", "ᄌ", "ᄍ",
            "ᄎ", "ᄏ", "ᄐ", "ᄑ", "ᄒ", "ᅡ",
            "ᅢ", "ᅣ", "ᅤ", "ᅥ", "ᅦ", "ᅧ",
            "ᅨ", "ᅩ", "ᅪ", "ᅫ", "ᅬ", "ᅭ",
            "ᅮ", "ᅯ", "ᅰ", "ᅱ", "ᅲ", "ᅳ",
            "ᅴ", "ᅵ", "ᆨ", "ᆫ", "ᆮ", "ᆯ",
            "ᆷ", "ᆸ", "ᆼ", "ㄸ", "!", "?",
            "…", ",", ".", "'", "-", "¿",
            "¡", "SP", "UNK",
        )

    private val SYMBOL_INDEX: Map<String, Int> = SYMBOLS.withIndex().associate { (index, symbol) -> symbol to index }

    /** `symbols.index("_")` upstream — the VITS "blank"/pad id, also used to frame BOS/EOS. */
    val PAD_ID: Int = requireIndex("_")

    /** `symbols.index("SP")` upstream — the word-break symbol real MeloTTS g2p emits between words. */
    val SP_ID: Int = requireIndex("SP")

    /** `symbols.index("UNK")` upstream — fail-safe id for any phoneme this frontend can't map. */
    val UNK_ID: Int = requireIndex("UNK")

    /** `language_id_map["EN"]` upstream (`{"ZH": 0, "JP": 1, "EN": 2, ...}`). */
    const val EN_LANGUAGE_ID: Int = 2

    /**
     * `language_tone_start_map["EN"]` upstream — EN's tone ids occupy the global tone-embedding
     * rows starting here (ZH takes 0..5, JP takes 6), so an EN local tone class (0..3) must be
     * offset by this before it is a valid `tone` id for the acoustic model.
     */
    const val EN_TONE_OFFSET: Int = 7

    /** The row for [symbolName], or [UNK_ID] if this table has no such symbol (fails closed, never crashes). */
    fun idFor(symbolName: String): Int = SYMBOL_INDEX[symbolName] ?: UNK_ID

    private fun requireIndex(symbol: String): Int =
        checkNotNull(SYMBOL_INDEX[symbol]) { "MeloSymbolTable is missing the required '$symbol' symbol" }
}
