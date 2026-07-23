package com.phonetts.core.download.builtin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

// Parses rhasspy/piper-voices' own published `voices.json` (the piper1-gpl voice family's
// manifest of every voice it ships) into [BuiltInModel]s at runtime, so the Piper voice list is
// always current with upstream instead of a hand-maintained snapshot (CLAUDE.md rule 1 - SSOT:
// the voice list lives in exactly one place, upstream's own manifest, not duplicated here). The
// mapping is mechanical: one BuiltInModel per voice key, taking that key's `.onnx`/`.onnx.json`
// repo paths verbatim - no engine code names any of these voices, and none of this ever runs
// hand-picking logic. Pure and Android-free: the caller (:app) fetches the JSON text and hands it
// here; this file makes no network call of its own, so it's fully unit-testable against a fixture.

private const val EXTRA_LOW_QUALITY = "x_low"
private const val EXTRA_LOW_DISPLAY = "extra-low"
private const val BYTES_PER_MB = 1_000_000.0
private const val PIPER_VOICE_ID_PREFIX = "piper-"

/** One voice's language facts, as upstream's `voices.json` publishes them. Only the fields the
 * display name needs are declared (kotlinx tolerates the rest via [PiperVoicesIndex.json]). */
@Serializable
data class PiperVoiceLanguage(
    @SerialName("name_english") val nameEnglish: String,
    @SerialName("country_english") val countryEnglish: String,
)

/** One file's metadata inside a voice's `files` map - keyed by its repo path (see
 * [PiperVoiceEntry.files]). Only [sizeBytes] is used; the digest isn't needed to build the
 * catalog entry. */
@Serializable
data class PiperVoiceFileMeta(
    @SerialName("size_bytes") val sizeBytes: Long,
)

/** One voice entry in upstream's `voices.json`, keyed by voice id (e.g. `en_US-lessac-medium`) in
 * the top-level object. [files] is keyed by each file's repo-relative path. */
@Serializable
data class PiperVoiceEntry(
    val key: String,
    val name: String,
    val language: PiperVoiceLanguage,
    val quality: String,
    val files: Map<String, PiperVoiceFileMeta>,
)

/**
 * Parses upstream's `voices.json` into the [BuiltInModel]s the Piper browse section renders.
 * Replaces the old hand-generated `PiperVoiceCatalog` - the exact same output shape, computed at
 * runtime from the SAME upstream manifest instead of a checked-in snapshot of it.
 */
object PiperVoicesIndex {
    /** The single repo every Piper voice (including [BuiltInCatalog.PIPER_LESSAC]) is published
     * from - the one place this literal lives (SSOT), reused wherever a Piper voice's repo id is
     * needed instead of being re-typed. */
    const val REPO_ID = "rhasspy/piper-voices"

    private val json = Json { ignoreUnknownKeys = true }
    private val entrySerializer = MapSerializer(String.serializer(), PiperVoiceEntry.serializer())

    /** True if [modelId] names a voice from this index (its ids are always `piper-<voice-key>`) -
     * lets a caller that only has an id (no [BuiltInModel] in hand, e.g. an already-downloaded
     * model) recognize it as a Piper voice without re-fetching or guessing. */
    fun isPiperVoiceId(modelId: String): Boolean = modelId.startsWith(PIPER_VOICE_ID_PREFIX)

    /**
     * @param voicesJson the raw text of upstream's `voices.json` (an object keyed by voice id).
     * @throws kotlinx.serialization.SerializationException if [voicesJson] isn't valid JSON in the
     * expected shape - the caller (:app) is responsible for catching this and failing closed (an
     * error state, never a guessed/stale list - CLAUDE.md rule 4's spirit applied to data, not
     * just model detection).
     */
    fun parse(voicesJson: String): List<BuiltInModel> {
        val entries = json.decodeFromString(entrySerializer, voicesJson)
        return entries.values.mapNotNull(::toBuiltInModel)
    }

    // A voice missing either required file (shouldn't happen for a real upstream manifest, but
    // fail closed rather than crash the whole browse list over one malformed entry) is skipped,
    // not guessed at.
    private fun toBuiltInModel(entry: PiperVoiceEntry): BuiltInModel? {
        val onnxPath = entry.files.keys.firstOrNull { it.endsWith(".onnx") } ?: return null
        val jsonPath = entry.files.keys.firstOrNull { it.endsWith(".onnx.json") } ?: return null
        val onnxSizeBytes = entry.files.getValue(onnxPath).sizeBytes
        return BuiltInModel(
            id = "$PIPER_VOICE_ID_PREFIX${entry.key}",
            displayName = displayName(entry),
            repoId = REPO_ID,
            approxSizeMb = (onnxSizeBytes / BYTES_PER_MB).roundToInt(),
            files =
                listOf(
                    BuiltInFile(repoPath = onnxPath, localName = onnxPath.substringAfterLast('/')),
                    BuiltInFile(repoPath = jsonPath, localName = jsonPath.substringAfterLast('/')),
                ),
        )
    }

    private fun displayName(entry: PiperVoiceEntry): String {
        val name = entry.name.split('_').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }
        val quality = if (entry.quality == EXTRA_LOW_QUALITY) EXTRA_LOW_DISPLAY else entry.quality
        return "Piper - $name (${entry.language.nameEnglish}, ${entry.language.countryEnglish}, $quality)"
    }
}
