package com.phonetts.engines.f5tts

/**
 * Parses F5-TTS's `vocab.txt` into a char->id map and converts text into vocab ids - SSOT
 * (CLAUDE.md rule 1): every symbol id this engine uses comes from the bundle's own `vocab.txt`,
 * never a hardcoded table.
 *
 * Mirrors `SWivid/F5-TTS`'s `list_str_to_idx` (`src/f5_tts/model/utils.py`, quoted in
 * `README-io.md`): `torch.tensor([vocab_char_map.get(c, 0) for c in t])` - one entry per Unicode
 * character of the input, id = the character's 0-based line number in `vocab.txt`. A character
 * absent from the vocabulary falls back to id `0` - the SAME fallback upstream uses (not a
 * guess): `get_tokenizer` asserts index 0 is the space character, and `vocab.txt`'s first line
 * IS a single space, so this degrades gracefully rather than crashing on an unrecognized symbol
 * (fail-closed in spirit, spec rule 4), though see `README-io.md` for why this is wrong for raw
 * Chinese script (pinyin conversion is not implemented here).
 */
object F5Vocab {
    /** One map entry per non-empty line of [vocabText]; id = 0-based line index. */
    fun parse(vocabText: String): Map<String, Int> {
        val lines = vocabText.lineSequence().toMutableList()
        // A trailing newline produces one extra empty "line" that is not a vocabulary entry.
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines.withIndex().associate { (index, line) -> line to index }
    }

    /** [text] mapped one Unicode character at a time through [charToId]; OOV falls back to id 0. */
    fun tokenIds(
        text: String,
        charToId: Map<String, Int>,
    ): LongArray = LongArray(text.length) { i -> (charToId[text[i].toString()] ?: FALLBACK_ID).toLong() }

    private const val FALLBACK_ID = 0
}
