package com.phonetts.engines.melotts

/**
 * Parses MeloTTS's `tokens.txt` companion file (MiaoMint/MeloTTS-ONNX `onnx_exports/en_v2`,
 * proven end-to-end by `scripts/model-verify/run_melo2.py`): one `"<symbol> <id>"` pair per line,
 * e.g. `_ 0`, `AA 7`. This IS the model's symbol table - read from the bundled model, not a
 * hardcoded constant (SSOT, spec rule 1). A malformed line is skipped rather than failing the
 * whole parse, so one stray line in a large export can't take down loading.
 */
object MeloTokens {
    /** Symbol name -> embedding row id. Malformed/blank lines are silently skipped. */
    fun parse(text: String): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        for (rawLine in text.lineSequence()) {
            parseLine(rawLine)?.let { (symbol, id) -> map[symbol] = id }
        }
        return map
    }

    private fun parseLine(rawLine: String): Pair<String, Int>? {
        val line = rawLine.trim('\r', '\n')
        if (line.isBlank()) return null
        val parts = line.split(' ')
        if (parts.size != EXPECTED_PARTS) return null
        val id = parts[1].toIntOrNull() ?: return null
        return parts[0] to id
    }

    private const val EXPECTED_PARTS = 2
}
