package com.phonetts.engines.kittentts

import com.phonetts.core.engine.Voice
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Parses KittenTTS's real voice table, `voices.npz` (docs/research/onnx-io.md, VALIDATED): a
 * plain ZIP archive of 8 `.npy` v1.0 arrays, each `(1, 256)` float32 - one StyleTTS2 style
 * embedding per named voice (`expr-voice-2-m`, `expr-voice-2-f`, ...). A voice's id/name is its
 * `.npy` entry name with the suffix stripped; the float payload itself is decoded by [NpyArray].
 *
 * Unlike `com.phonetts.engines.kokoro.KokoroVoiceTable`'s JSON stand-in for Kokoro's own `.npz`,
 * this genuinely parses the binary format: [com.phonetts.core.model.ModelBundle] excludes `.npz`
 * from its text side files (it is weight-shaped binary, not fingerprinting-sized text), so
 * `inspect()`/`forcedMatch()` can only confirm a `voices.npz` file is *present* by name - they
 * cannot read its contents. [KittenEngine.load] is what has real bytes (via its injected
 * `fileReader` seam) to hand this parser.
 */
object KittenVoiceTable {
    data class Entry(val voice: Voice, val embedding: FloatArray)

    /** Parses raw `.npz` [bytes] into ordered voice entries. Malformed/empty input yields none. */
    fun parse(bytes: ByteArray): List<Entry> = runCatching { readEntries(bytes) }.getOrDefault(emptyList())

    private fun readEntries(bytes: ByteArray): List<Entry> {
        val entries = mutableListOf<Entry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                addNpyEntry(zip, entry.name, entries)
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun addNpyEntry(
        zip: ZipInputStream,
        entryName: String,
        entries: MutableList<Entry>,
    ) {
        if (!entryName.endsWith(NPY_SUFFIX)) return
        val voiceId = entryName.removeSuffix(NPY_SUFFIX)
        val embedding = NpyArray.parseFloats(zip.readBytes())
        entries.add(Entry(Voice(id = voiceId, name = voiceId, language = KittenEngine.LANGUAGE), embedding))
    }

    private const val NPY_SUFFIX = ".npy"
}
