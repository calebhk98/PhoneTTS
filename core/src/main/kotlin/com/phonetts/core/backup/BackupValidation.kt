package com.phonetts.core.backup

import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.engine.VoiceBlend
import com.phonetts.core.metrics.BenchmarkRecord
import com.phonetts.core.metrics.UsageStatsSnapshot
import com.phonetts.core.session.SessionState
import com.phonetts.core.store.FavoriteVoiceRef
import com.phonetts.core.store.FlaggedModel

/**
 * A [BackupDocument] after every entry has been checked against the invariant the live domain type
 * itself would enforce, with anything that fails DROPPED rather than the whole restore aborted -
 * "a valid file with one corrupt entry" (issue #149's explicit failure case) must still restore
 * everything else. [skippedCount] is the total number of individual entries dropped across every
 * list, so the restore UI can say "imported everything except N malformed entries" instead of
 * silently losing them.
 */
data class ValidatedBackup(
    val library: List<BackupLibraryDocument>,
    val resumePositions: List<BackupResumePosition>,
    val favoriteVoices: List<FavoriteVoiceRef>,
    val favoriteModels: List<String>,
    val flaggedModels: List<FlaggedModel>,
    val blendedVoices: List<BlendedVoiceSpec>,
    val session: SessionState,
    val usageStats: UsageStatsSnapshot,
    val benchmarkRecords: List<BenchmarkRecord>,
    val skippedCount: Int,
)

/**
 * Validates a [BackupDocument]'s contents entry-by-entry (issue #149) - called once, before
 * [BackupRestorer] writes anything to any store, so "validate the whole document first, then write"
 * holds even though individual bad entries are tolerated.
 */
object BackupValidation {
    fun validate(document: BackupDocument): ValidatedBackup {
        var skipped = 0

        fun <T> List<T>.keepValid(isValid: (T) -> Boolean): List<T> {
            val (kept, dropped) = partition(isValid)
            skipped += dropped.size
            return kept
        }

        val library =
            document.library.keepValid { it.id.isNotBlank() }
                .distinctBy { it.id }
        val resumePositions = document.resumePositions.keepValid(::isValidPosition)
        val favoriteVoices =
            document.favorites.voices
                .keepValid { it.modelId.isNotBlank() && it.voiceId.isNotBlank() }
                .distinct()
        val favoriteModels = document.favorites.models.keepValid { it.isNotBlank() }.distinct()
        val flaggedModels = document.favorites.flagged.keepValid { it.modelId.isNotBlank() }.distinctBy { it.modelId }
        val blendedVoices =
            document.blendedVoices.mapNotNull(::toValidSpec).also {
                skipped += document.blendedVoices.size - it.size
            }
        val benchmarkRecords =
            document.benchmarkHistory.mapNotNull(::toValidRecord)
                .also { skipped += document.benchmarkHistory.size - it.size }

        return ValidatedBackup(
            library = library,
            resumePositions = resumePositions,
            favoriteVoices = favoriteVoices,
            favoriteModels = favoriteModels,
            flaggedModels = flaggedModels,
            blendedVoices = blendedVoices,
            session = sanitizeSession(document.session),
            usageStats = sanitizeUsageStats(document.usageStats),
            benchmarkRecords = benchmarkRecords,
            skippedCount = skipped,
        )
    }

    private fun isValidPosition(position: BackupResumePosition): Boolean =
        position.documentId.isNotBlank() &&
            position.engineId.isNotBlank() &&
            position.voiceId.isNotBlank() &&
            position.sentenceIndex >= 0 &&
            position.speed.isFinite() &&
            position.speed > 0f

    private fun toValidSpec(dto: BackupBlendedVoice): BlendedVoiceSpec? {
        val validIds =
            dto.id.isNotBlank() &&
                dto.modelId.isNotBlank() &&
                dto.voiceAId.isNotBlank() &&
                dto.voiceBId.isNotBlank()
        val validWeight = dto.weight.isFinite() && dto.weight in VoiceBlend.MIN_WEIGHT..VoiceBlend.MAX_WEIGHT
        if (!validIds || !validWeight) return null
        return BlendedVoiceSpec(dto.id, dto.name, dto.modelId, dto.voiceAId, dto.voiceBId, dto.weight)
    }

    private fun toValidRecord(dto: BackupBenchmarkRecord): BenchmarkRecord? =
        runCatching { BenchmarkRecord(dto.engineId, dto.device, dto.timestampMillis, dto.realTimeFactor) }.getOrNull()

    // SessionState has no per-field invariants worth dropping the whole singleton over; the one
    // value with a real bound (tempoFactor, TempoStretch's WSOLA range) is clamped rather than
    // discarded, since there is no sensible "skip this session" fallback for a single current state.
    private fun sanitizeSession(session: SessionState): SessionState =
        session.copy(
            reader = session.reader.copy(tempoFactor = session.reader.tempoFactor.coerceIn(TEMPO_MIN, TEMPO_MAX)),
        )

    // Cumulative counters can never legitimately be negative; a corrupt/hand-edited file gets its
    // negatives floored to zero rather than the whole usage-stats domain being thrown away.
    private fun sanitizeUsageStats(stats: BackupUsageStats): UsageStatsSnapshot =
        UsageStatsSnapshot(
            totalListeningMillis = stats.totalListeningMillis.coerceAtLeast(0L),
            totalWordsSpoken = stats.totalWordsSpoken.coerceAtLeast(0L),
            generationCount = stats.generationCount.coerceAtLeast(0L),
            playCountByModelId =
                stats.playCountByModelId.filterValues { it >= 0L }.mapValues {
                    it.value.coerceAtLeast(
                        0L,
                    )
                },
        )

    private const val TEMPO_MIN = 0.1f
    private const val TEMPO_MAX = 10f
}
