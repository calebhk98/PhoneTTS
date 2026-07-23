package com.phonetts.engines.melotts

/**
 * Parses MeloTTS's `lexicon.txt` companion file (MiaoMint/MeloTTS-ONNX `onnx_exports/en_v2`,
 * ~4.8 MB - proven end-to-end by `scripts/model-verify/run_melo2.py`): one
 * `"<word> p1 p2 ... pN t1 t2 ... tN"` line per entry, e.g. `hello hh ah l ow 7 8 7 9`. This IS
 * the model's G2P dictionary - read from the bundled model rather than a hardcoded phoneme map
 * (SSOT, spec rule 1), which is what let this replace the old espeak-IPA-guessing frontend.
 *
 * The word is lowercased on lookup (matches the reference recipe's `text.lower()` + dict built
 * from `parts[0].lower()`). A line whose phoneme/tone halves don't match in count, or whose tone
 * fields aren't integers, is skipped rather than failing the whole parse.
 */
object MeloLexicon {
    data class Entry(val phonemes: List<String>, val tones: List<Int>)

    fun parse(text: String): Map<String, Entry> {
        val map = LinkedHashMap<String, Entry>()
        for (rawLine in text.lineSequence()) {
            parseLine(rawLine)?.let { (word, entry) -> map[word] = entry }
        }
        return map
    }

    private fun parseLine(rawLine: String): Pair<String, Entry>? {
        val parts = rawLine.trim().split(WHITESPACE_REGEX)
        if (parts.size < MIN_PARTS) return null
        val word = parts[0].lowercase()
        val rest = parts.drop(1)
        if (rest.size % 2 != 0) return null

        val half = rest.size / 2
        val phones = rest.subList(0, half)
        val tones = rest.subList(half, rest.size).map { it.toIntOrNull() ?: return null }
        return word to Entry(phones, tones)
    }

    private const val MIN_PARTS = 3
    private val WHITESPACE_REGEX = Regex("\\s+")
}
