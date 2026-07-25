package com.phonetts.core.backup

import com.phonetts.core.session.SessionState
import com.phonetts.core.store.FavoriteVoiceRef
import com.phonetts.core.store.FlaggedModel
import kotlinx.serialization.Serializable

/**
 * One saved reading-library document: metadata AND full text (issue #149). A dedicated DTO rather
 * than reusing [com.phonetts.core.prefs.LibraryDocument] directly - that class isn't `@Serializable`
 * and its shape is [com.phonetts.core.prefs.DocumentLibrary]'s to evolve; this is the backup file's
 * own frozen-per-[BackupDocument.schemaVersion] wire contract, built FROM the live class by
 * [BackupExporter], never a second place that invents the fact.
 */
@Serializable
data class BackupLibraryDocument(
    val id: String,
    val title: String,
    val text: String,
    val savedAtMillis: Long,
)

/** Mirrors [com.phonetts.core.prefs.DocumentPosition] - see [BackupLibraryDocument] for why this is a
 * separate DTO rather than the live (non-`@Serializable`) class. */
@Serializable
data class BackupResumePosition(
    val documentId: String,
    val engineId: String,
    val voiceId: String,
    val speed: Float,
    val sentenceIndex: Int,
)

/**
 * The whole persisted Favorites document, wire-format for backup. [FavoriteVoiceRef] and
 * [FlaggedModel] are reused AS-IS (both are already `@Serializable` in
 * [com.phonetts.core.store.FavoritesStore]) - only this container is new, mirroring that store's
 * three lists so nothing about a favorite's shape is duplicated, only re-packaged.
 */
@Serializable
data class BackupFavorites(
    val voices: List<FavoriteVoiceRef> = emptyList(),
    val models: List<String> = emptyList(),
    val flagged: List<FlaggedModel> = emptyList(),
)

/** Mirrors [com.phonetts.core.engine.BlendedVoiceSpec] - see [BackupLibraryDocument] for why this is a
 * separate DTO. Only the recipe travels (two source voice ids + weight), never audio - same as the
 * live store. */
@Serializable
data class BackupBlendedVoice(
    val id: String,
    val name: String,
    val modelId: String,
    val voiceAId: String,
    val voiceBId: String,
    val weight: Float,
)

/** Mirrors the cumulative totals in [com.phonetts.core.metrics.UsageStatsSnapshot] (that class isn't
 * `@Serializable`, and its backing `UsageStatsState` is private to its own file). */
@Serializable
data class BackupUsageStats(
    val totalListeningMillis: Long = 0L,
    val totalWordsSpoken: Long = 0L,
    val generationCount: Long = 0L,
    val playCountByModelId: Map<String, Long> = emptyMap(),
)

/**
 * Mirrors [com.phonetts.core.metrics.BenchmarkRecord] - kept as a lenient DTO with NO `require()`
 * invariants (unlike the live class) so a single corrupt sample decodes successfully as data and can
 * be dropped by [BackupRestorer]'s sanitization pass, rather than throwing mid-JSON-decode and
 * failing the ENTIRE file over one bad number (rule 4's fail-closed spirit: refuse only what
 * genuinely can't be trusted, not everything near it).
 */
@Serializable
data class BackupBenchmarkRecord(
    val engineId: String,
    val device: String,
    val timestampMillis: Long,
    val realTimeFactor: Double,
)

/**
 * The one versioned, self-describing local backup document (issue #149): the reading library
 * (metadata AND full text), resume positions, favorites/flags, blended voices, session/reader
 * settings, and cumulative usage stats/benchmark history - everything this app persists EXCEPT
 * model weights, which are deliberately and explicitly never included (hundreds of MB, and
 * re-downloadable - see [WEIGHTS_NOTE], which this file both carries AND the app's UI states
 * honestly, per rule 7's "be honest about what can't be verified/included").
 *
 * [schemaVersion] is the single fact [BackupCodec] uses to decide whether a file can be applied at
 * all: newer than [CURRENT_SCHEMA_VERSION] means "written by a build we don't understand yet" and is
 * refused outright; older than [MIN_SUPPORTED_SCHEMA_VERSION] means "no longer supported" and is
 * refused too - in both cases nothing is partially applied. Every field otherwise has a default so
 * `ignoreUnknownKeys` plus a field this version doesn't carry both decode cleanly; that leniency is
 * for forward/backward JUNK tolerance only, never a substitute for the explicit version check.
 */
@Serializable
data class BackupDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtMillis: Long,
    val modelWeightsIncluded: Boolean = false,
    val notice: String = WEIGHTS_NOTE,
    val library: List<BackupLibraryDocument> = emptyList(),
    val resumePositions: List<BackupResumePosition> = emptyList(),
    val favorites: BackupFavorites = BackupFavorites(),
    val blendedVoices: List<BackupBlendedVoice> = emptyList(),
    val session: SessionState = SessionState(),
    val usageStats: BackupUsageStats = BackupUsageStats(),
    val benchmarkHistory: List<BackupBenchmarkRecord> = emptyList(),
) {
    companion object {
        /** The schema this build writes, and fully understands on read. Bump on any breaking shape change. */
        const val CURRENT_SCHEMA_VERSION = 1

        /** Oldest schema this build still knows how to read. Below this: refuse (see [BackupCodec]). */
        const val MIN_SUPPORTED_SCHEMA_VERSION = 1

        /** The honest, user-facing statement of what this file deliberately leaves out. */
        const val WEIGHTS_NOTE =
            "Model weights are NOT included in this backup - they are large, re-downloadable files. " +
                "Re-download any models you use from Browse after restoring."
    }
}
