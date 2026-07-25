package com.phonetts.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The only field [BackupCodec.decode] reads BEFORE deciding whether the rest is worth parsing at
 * all - see its kdoc for why the version check happens first, on its own lenient probe. */
@Serializable
private data class BackupVersionProbe(val schemaVersion: Int = BackupDocument.CURRENT_SCHEMA_VERSION)

/**
 * Why a raw backup file could, or could not, be turned into a [BackupDocument] - see
 * [BackupCodec.decode]. Every branch here means "nothing was applied yet" - this is a pure parse
 * result, never a partial write (that guarantee lives in [BackupRestorer]).
 */
sealed interface BackupDecodeResult {
    /** Parsed cleanly and its [BackupDocument.schemaVersion] is one this build understands. */
    data class Success(val document: BackupDocument) : BackupDecodeResult

    /** Not JSON, truncated, or otherwise unparseable - garbage bytes. Nothing to apply. */
    data class Corrupt(val reason: String) : BackupDecodeResult

    /**
     * Parsed enough to read [BackupDocument.schemaVersion], but it falls outside the range this
     * build supports - refused explicitly rather than guessed at (rule 4's fail-closed spirit).
     * [fileVersion] above [BackupDocument.CURRENT_SCHEMA_VERSION] means "written by a newer app
     * build"; below [BackupDocument.MIN_SUPPORTED_SCHEMA_VERSION] means "no longer supported".
     * Either way: refuse, never partially apply.
     */
    data class UnsupportedVersion(val fileVersion: Int) : BackupDecodeResult
}

/**
 * Turns a [BackupDocument] into the one JSON blob the export file holds, and back (issue #149). The
 * only place that owns the backup file's wire format - `:app` never touches JSON directly, only this
 * codec and the [BackupDocument] shape it reads/writes.
 */
object BackupCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    private val versionProbeJson = Json { ignoreUnknownKeys = true }

    /** Serializes [document] to the file's on-disk JSON text. */
    fun encode(document: BackupDocument): String = json.encodeToString(BackupDocument.serializer(), document)

    /**
     * Parses [raw] into a [BackupDecodeResult]. The schema version is checked BEFORE the full
     * document is decoded, so a file this build genuinely can't understand is refused on that basis
     * alone - never on how much of it happens to still parse under `ignoreUnknownKeys`.
     */
    fun decode(raw: String): BackupDecodeResult {
        if (raw.isBlank()) return BackupDecodeResult.Corrupt("File is empty.")
        val version = peekSchemaVersion(raw) ?: return BackupDecodeResult.Corrupt("Not a recognizable backup file.")
        if (version > BackupDocument.CURRENT_SCHEMA_VERSION || version < BackupDocument.MIN_SUPPORTED_SCHEMA_VERSION) {
            return BackupDecodeResult.UnsupportedVersion(version)
        }
        return runCatching { json.decodeFromString(BackupDocument.serializer(), raw) }
            .fold(
                onSuccess = { BackupDecodeResult.Success(it) },
                onFailure = { BackupDecodeResult.Corrupt(it.message ?: "Could not parse backup contents.") },
            )
    }

    private fun peekSchemaVersion(raw: String): Int? =
        runCatching {
            versionProbeJson.decodeFromString(
                BackupVersionProbe.serializer(),
                raw,
            ).schemaVersion
        }.getOrNull()
}
