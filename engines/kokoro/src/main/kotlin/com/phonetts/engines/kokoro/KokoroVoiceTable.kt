package com.phonetts.engines.kokoro

import com.phonetts.core.engine.Voice

/**
 * Builds Kokoro's per-voice tables from the REAL repo format
 * (`onnx-community/Kokoro-82M-v1.0-ONNX`, VALIDATED via `scripts/model-verify/run_kokoro.py`): one
 * `voices/<name>.bin` file per voice — NOT a `voices.json` name->embedding array and NOT a single
 * zipped `voices.npz` archive like KittenTTS. Because each voice is its own named file, its id is
 * known from the bundle's file NAMES alone (see [KokoroEngine.inspect]); only the [Entry.table]
 * itself (the raw [KokoroVoiceBinReader.ROWS] x [KokoroVoiceBinReader.COLS] floats) requires
 * reading the file's bytes, which happens later in [KokoroEngine.load].
 *
 * A voice's language is guessed from the first letter of its id, per Kokoro's own voice-naming
 * convention (e.g. `af_heart` -> American English, `bf_emma` -> British English); an unrecognized
 * prefix falls back to [DEFAULT_LANGUAGE] rather than guessing further.
 */
object KokoroVoiceTable {
    data class Entry(val voice: Voice, val table: FloatArray)

    /**
     * The [Voice] metadata for one voice id (a `.bin` file name with the `voices/` directory
     * prefix and `.bin` suffix already stripped). No byte content is needed for this, so
     * `inspect()`/`forcedMatch()` can build the full voice list from file names alone.
     */
    fun voiceFor(id: String): Voice = Voice(id = id, name = id, language = languageFor(id))

    /**
     * Decodes one voice's raw `.bin` [bytes] into a full [Entry], pairing [voiceFor]'s metadata
     * with [KokoroVoiceBinReader]'s table. Null if [bytes] isn't the expected [510, 256] size.
     */
    fun entryFor(
        id: String,
        bytes: ByteArray,
    ): Entry? {
        val table = KokoroVoiceBinReader.parseTable(bytes) ?: return null
        return Entry(voiceFor(id), table)
    }

    /**
     * Decodes a full `id -> bytes` map (one entry per loaded `voices/<name>.bin` file) into ordered
     * entries. A file whose byte count doesn't match [KokoroVoiceBinReader.EXPECTED_BYTE_COUNT] is
     * skipped, not thrown — [KokoroEngine.load] fails closed on the whole table via emptiness, not
     * a crash triggered by one bad file.
     */
    fun parse(voiceFiles: Map<String, ByteArray>): List<Entry> =
        voiceFiles.toSortedMap().mapNotNull { (id, bytes) -> entryFor(id, bytes) }

    // Kokoro voice-naming convention (onnx-community/Kokoro-82M-v1.0-ONNX `voices/` listing): the
    // first letter of the id names the language/locale, the second the speaker gender — gender
    // doesn't affect language routing, so only the first letter is keyed here.
    private fun languageFor(voiceId: String): String {
        val prefix = voiceId.firstOrNull() ?: return DEFAULT_LANGUAGE
        return LANGUAGE_BY_PREFIX[prefix] ?: DEFAULT_LANGUAGE
    }

    const val DEFAULT_LANGUAGE = "en-us"

    private val LANGUAGE_BY_PREFIX =
        mapOf(
            'a' to "en-us",
            'b' to "en-gb",
            'j' to "ja",
            'z' to "cmn",
            'e' to "es",
            'f' to "fr-fr",
            'h' to "hi",
            'i' to "it",
            'p' to "pt-br",
        )
}
