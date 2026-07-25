package com.phonetts.core.backup

import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.metrics.BenchmarkRecord
import com.phonetts.core.metrics.UsageStatsSnapshot
import com.phonetts.core.metrics.UsageStatsStore
import com.phonetts.core.prefs.BlendedVoiceStore
import com.phonetts.core.prefs.DocumentLibrary
import com.phonetts.core.prefs.DocumentMemory
import com.phonetts.core.prefs.DocumentPosition
import com.phonetts.core.session.SessionState
import com.phonetts.core.session.SessionStateStore
import com.phonetts.core.store.FavoritesStore
import kotlin.math.max

/**
 * How a restore reconciles the file's contents with what is already on the device (issue #149).
 *
 * [MERGE] only ADDS/updates - an id-keyed collection (library documents, favorites, blended voices,
 * benchmark samples) never loses a LOCAL-only entry the file doesn't mention, and a cumulative
 * counter (usage stats) takes the larger of the two values per field. Both choices make merge
 * idempotent: applying the same file twice leaves the same state (max(x, x) = x, upsert-by-id twice
 * over).
 *
 * [REPLACE] makes each restored domain match the file EXACTLY - anything local-only that the file
 * doesn't mention is deleted. Also idempotent: applying the same file twice, in replace mode, leaves
 * exactly the file's contents both times.
 *
 * Session/reader settings are a single "what was I doing last" value, not an id-keyed collection, so
 * both modes simply adopt the file's value - there is no meaningful "merge" of two singleton states.
 */
enum class RestoreMode { MERGE, REPLACE }

/**
 * What a restore actually changed, for the confirmation/result UI. [entriesSkipped] counts every
 * malformed INDIVIDUAL entry [BackupValidation] dropped - never the whole file (see [BackupRestorer]).
 */
data class BackupRestoreSummary(
    val mode: RestoreMode,
    val documentsWritten: Int,
    val documentsRemoved: Int,
    val resumePositionsWritten: Int,
    val favoriteVoicesWritten: Int,
    val favoriteModelsWritten: Int,
    val flaggedModelsWritten: Int,
    val blendedVoicesWritten: Int,
    val benchmarkRecordsWritten: Int,
    val entriesSkipped: Int,
)

/**
 * Applies an already-decoded [BackupDocument] onto [stores] (issue #149) - the `:core` half of
 * "Import my data". Never called with raw file bytes: [BackupCodec.decode] must succeed first, so a
 * corrupt/wrong-version FILE never reaches here at all (rule 4, fail closed at the top).
 *
 * All validation for every domain happens up front, via [BackupValidation.validate], before this
 * writes to ANY store - "validate the whole document first, then write" - so a bad individual entry
 * is dropped rather than aborting the whole restore, but nothing bad is ever written either. Each
 * `restoreX` helper below only calls the target store's own public mutators (never its backing
 * file), so a restore can never desync from how that store actually persists.
 */
object BackupRestorer {
    fun restore(
        document: BackupDocument,
        stores: BackupStores,
        mode: RestoreMode,
    ): BackupRestoreSummary {
        val validated = BackupValidation.validate(document)

        val library = restoreLibrary(stores.library, validated.library, mode)
        restoreResumePositions(stores.documentMemory, validated.resumePositions, library.removedIds, mode)
        val favorites = restoreFavorites(stores.favoritesStore, validated, mode)
        val blendedCount = restoreBlendedVoices(stores.blendedVoiceStore, validated.blendedVoices, mode)
        restoreSession(stores.sessionStateStore, validated.session)
        restoreUsageStats(stores.usageStatsStore, validated.usageStats, mode)
        val benchmarkCount = restoreBenchmarkHistory(stores.benchmarkHistory, validated.benchmarkRecords, mode)

        return BackupRestoreSummary(
            mode = mode,
            documentsWritten = library.writtenCount,
            documentsRemoved = library.removedIds.size,
            resumePositionsWritten = validated.resumePositions.size,
            favoriteVoicesWritten = favorites.voicesWritten,
            favoriteModelsWritten = favorites.modelsWritten,
            flaggedModelsWritten = favorites.flaggedWritten,
            blendedVoicesWritten = blendedCount,
            benchmarkRecordsWritten = benchmarkCount,
            entriesSkipped = validated.skippedCount,
        )
    }

    private data class LibraryRestoreResult(val writtenCount: Int, val removedIds: Set<String>)

    // REPLACE deletes any local document the file doesn't mention FIRST, then MERGE's own add-by-id
    // step runs for both modes (DocumentLibrary.add already replaces-by-id, so re-adding an unchanged
    // document is a harmless no-op - the source of both modes' idempotency here).
    private fun restoreLibrary(
        library: DocumentLibrary,
        entries: List<BackupLibraryDocument>,
        mode: RestoreMode,
    ): LibraryRestoreResult {
        val importIds = entries.map { it.id }.toSet()
        val removedIds =
            if (mode == RestoreMode.REPLACE) {
                library.list().map { it.id }.filterNot { it in importIds }.toSet()
            } else {
                emptySet()
            }
        removedIds.forEach(library::delete)
        entries.forEach { library.add(it.id, it.text, it.savedAtMillis, it.title.ifBlank { null }) }
        return LibraryRestoreResult(entries.size, removedIds)
    }

    // DocumentMemory has no key-enumeration of its own (issue #149's constraint), so REPLACE mode
    // only forgets positions for documents [restoreLibrary] just deleted - the correct "no longer
    // relevant" set - rather than needing to enumerate every position ever recorded.
    private fun restoreResumePositions(
        documentMemory: DocumentMemory,
        positions: List<BackupResumePosition>,
        removedDocumentIds: Set<String>,
        mode: RestoreMode,
    ) {
        if (mode == RestoreMode.REPLACE) {
            removedDocumentIds.forEach(documentMemory::forget)
        }
        positions.forEach { position ->
            documentMemory.record(
                DocumentPosition(
                    position.documentId,
                    position.engineId,
                    position.voiceId,
                    position.speed,
                    position.sentenceIndex,
                ),
            )
        }
    }

    private data class FavoritesRestoreResult(val voicesWritten: Int, val modelsWritten: Int, val flaggedWritten: Int)

    private fun restoreFavorites(
        store: FavoritesStore,
        validated: ValidatedBackup,
        mode: RestoreMode,
    ): FavoritesRestoreResult {
        if (mode == RestoreMode.REPLACE) {
            val importVoices = validated.favoriteVoices.toSet()
            val staleVoices = store.favoriteVoices().filterNot { it in importVoices }
            staleVoices.forEach { store.setFavoriteVoice(it.modelId, it.voiceId, false) }
            val importModels = validated.favoriteModels.toSet()
            store.favoriteModels().filterNot { it in importModels }.forEach { store.setFavoriteModel(it, false) }
            val importFlagged = validated.flaggedModels.map { it.modelId }.toSet()
            val staleFlags = store.flaggedModels().map { it.modelId }.filterNot { it in importFlagged }
            staleFlags.forEach(store::unflagModel)
        }
        validated.favoriteVoices.forEach { store.setFavoriteVoice(it.modelId, it.voiceId, true) }
        validated.favoriteModels.forEach { store.setFavoriteModel(it, true) }
        validated.flaggedModels.forEach { store.flagModel(it.modelId, it.reason, it.atMs) }
        return FavoritesRestoreResult(
            voicesWritten = validated.favoriteVoices.size,
            modelsWritten = validated.favoriteModels.size,
            flaggedWritten = validated.flaggedModels.size,
        )
    }

    // REPLACE clobbers every model touched by EITHER side (store.allModelIds() union the file's own
    // model ids) so a model with local-only mixes the file never mentions ends up mix-free, matching
    // "make it match the file exactly".
    private fun restoreBlendedVoices(
        store: BlendedVoiceStore,
        specs: List<BlendedVoiceSpec>,
        mode: RestoreMode,
    ): Int {
        if (mode == RestoreMode.REPLACE) {
            val importByModel = specs.groupBy { it.modelId }
            val touchedModelIds = store.allModelIds() + importByModel.keys
            touchedModelIds.forEach { modelId ->
                wipeModelMixesNotInImport(store, modelId, importByModel[modelId].orEmpty())
            }
        }
        specs.forEach(store::save)
        return specs.size
    }

    private fun wipeModelMixesNotInImport(
        store: BlendedVoiceStore,
        modelId: String,
        importedForModel: List<BlendedVoiceSpec>,
    ) {
        val importIds = importedForModel.map { it.id }.toSet()
        val staleIds = store.forModel(modelId).map { it.id }.filterNot { it in importIds }
        staleIds.forEach { store.remove(modelId, it) }
    }

    // A single "what was I doing last" value - both modes simply adopt the file's, see [RestoreMode].
    private fun restoreSession(
        store: SessionStateStore,
        session: SessionState,
    ) {
        store.updateReader { session.reader }
        store.updateBrowse { session.browse }
        store.updateManage { session.manage }
        store.updateBenchmark { session.benchmark }
    }

    // MERGE takes the larger of the two totals per field/model - the only choice that is both
    // sensible for a monotonic counter AND idempotent (max(x, x) = x). REPLACE adopts the file's
    // totals outright.
    private fun restoreUsageStats(
        store: UsageStatsStore,
        imported: UsageStatsSnapshot,
        mode: RestoreMode,
    ) {
        val toWrite =
            if (mode == RestoreMode.REPLACE) {
                imported
            } else {
                val current = store.snapshot()
                UsageStatsSnapshot(
                    totalListeningMillis = max(current.totalListeningMillis, imported.totalListeningMillis),
                    totalWordsSpoken = max(current.totalWordsSpoken, imported.totalWordsSpoken),
                    generationCount = max(current.generationCount, imported.generationCount),
                    playCountByModelId = mergeCounts(current.playCountByModelId, imported.playCountByModelId),
                )
            }
        store.restoreSnapshot(toWrite)
    }

    private fun mergeCounts(
        current: Map<String, Long>,
        imported: Map<String, Long>,
    ): Map<String, Long> =
        (current.keys + imported.keys).associateWith { key -> max(current[key] ?: 0L, imported[key] ?: 0L) }

    // MERGE unions existing + imported samples per engine+device (deduplicated by full equality, so
    // re-merging the same file twice is a no-op), then lets replaceHistory re-apply the MAX_SAMPLES
    // cap. REPLACE clobbers every key touched by either side, clearing a local-only key entirely.
    private fun restoreBenchmarkHistory(
        history: BenchmarkHistory,
        records: List<BenchmarkRecord>,
        mode: RestoreMode,
    ): Int {
        val importByKey = records.groupBy { it.engineId to it.device }
        val touchedKeys = (history.allKeys() + importByKey.keys).distinct()
        touchedKeys.forEach { (engineId, device) ->
            val importGroup = importByKey[engineId to device].orEmpty()
            applyBenchmarkKey(history, engineId, device, importGroup, mode)
        }
        return records.size
    }

    private fun applyBenchmarkKey(
        history: BenchmarkHistory,
        engineId: String,
        device: String,
        importGroup: List<BenchmarkRecord>,
        mode: RestoreMode,
    ) {
        if (mode == RestoreMode.REPLACE) {
            history.replaceHistory(engineId, device, importGroup)
            return
        }
        if (importGroup.isEmpty()) return
        val merged = (history.history(engineId, device) + importGroup).distinct()
        history.replaceHistory(engineId, device, merged)
    }
}
