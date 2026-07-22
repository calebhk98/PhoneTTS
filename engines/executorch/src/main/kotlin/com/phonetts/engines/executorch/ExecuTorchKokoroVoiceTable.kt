package com.phonetts.engines.executorch

import com.phonetts.core.engine.Voice

/**
 * Builds Kokoro-on-ExecuTorch's per-voice table entries from the REAL repo format (VERIFIED via
 * Hugging Face `software-mansion/react-native-executorch-kokoro`): one `voices/<name>.bin` file
 * per voice, so a voice's id is known from the bundle's file NAMES alone (see
 * `ExecuTorchKokoroEngine.inspect`) — only [Entry.table] itself needs the file's bytes, read later
 * at `load()`. Mirrors `:engines:kokoro`'s `KokoroVoiceTable` shape (duplicated, not shared — see
 * [ExecuTorchKokoroVoiceBinReader]'s kdoc for why).
 *
 * The real repo's `voices/` listing (VERIFIED) covers a superset of `:engines:kokoro`'s prefixes,
 * adding a German pair (`df_anna`, `dm_*`) — folded into [LANGUAGE_BY_PREFIX] here.
 */
object ExecuTorchKokoroVoiceTable {
    data class Entry(val voice: Voice, val table: FloatArray)

    /** [Voice] metadata for one voice id, needing no byte content — file NAMES are enough. */
    fun voiceFor(id: String): Voice = Voice(id = id, name = id, language = languageFor(id))

    /** Decodes one voice's raw `.bin` [bytes] into a full [Entry], or null if malformed. */
    fun entryFor(
        id: String,
        bytes: ByteArray,
    ): Entry? {
        val table = ExecuTorchKokoroVoiceBinReader.parseTable(bytes) ?: return null
        return Entry(voiceFor(id), table)
    }

    /** Decodes a full `id -> bytes` map into ordered entries, skipping any malformed file. */
    fun parse(voiceFiles: Map<String, ByteArray>): List<Entry> =
        voiceFiles.toSortedMap().mapNotNull { (id, bytes) -> entryFor(id, bytes) }

    private fun languageFor(voiceId: String): String {
        val prefix = voiceId.firstOrNull() ?: return DEFAULT_LANGUAGE
        return LANGUAGE_BY_PREFIX[prefix] ?: DEFAULT_LANGUAGE
    }

    const val DEFAULT_LANGUAGE = "en-us"

    private val LANGUAGE_BY_PREFIX =
        mapOf(
            'a' to "en-us",
            'b' to "en-gb",
            'd' to "de",
            'j' to "ja",
            'z' to "cmn",
            'e' to "es",
            'f' to "fr-fr",
            'h' to "hi",
            'i' to "it",
            'p' to "pt-br",
        )
}
