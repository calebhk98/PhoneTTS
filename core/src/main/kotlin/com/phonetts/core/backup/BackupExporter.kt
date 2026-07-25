package com.phonetts.core.backup

import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.metrics.BenchmarkRecord
import com.phonetts.core.metrics.UsageStatsSnapshot
import com.phonetts.core.prefs.DocumentPosition
import com.phonetts.core.prefs.LibraryDocument
import com.phonetts.core.store.FavoritesStore

/**
 * Builds the one exportable [BackupDocument] snapshot from the live [BackupStores] (issue #149) -
 * the `:core` half of "Export my data". Reads each store through its OWN public API, never its
 * backing file, so this can never drift out of sync with how a store actually shapes its data - it
 * only re-packages what is already there.
 *
 * Deliberately does NOT touch model weights, download state, or anything engine/runtime-related -
 * see [BackupDocument.WEIGHTS_NOTE]. [nowMillis] is caller-supplied, never read from the wall clock
 * here, matching every other `:core` seam.
 */
object BackupExporter {
    fun export(
        stores: BackupStores,
        nowMillis: Long,
    ): BackupDocument {
        val documents = stores.library.list()
        return BackupDocument(
            exportedAtMillis = nowMillis,
            library = documents.mapNotNull { meta -> stores.library.get(meta.id) }.map(::toBackupDocument),
            resumePositions =
                documents.mapNotNull { meta -> stores.documentMemory.resume(meta.id) }.map(::toBackupPosition),
            favorites = toBackupFavorites(stores.favoritesStore),
            blendedVoices =
                stores.blendedVoiceStore.allModelIds()
                    .flatMap(stores.blendedVoiceStore::forModel)
                    .map(::toBackupBlendedVoice),
            session = stores.sessionStateStore.current(),
            usageStats = toBackupUsageStats(stores.usageStatsStore.snapshot()),
            benchmarkHistory =
                stores.benchmarkHistory.allKeys()
                    .flatMap { (engineId, device) -> stores.benchmarkHistory.history(engineId, device) }
                    .map(::toBackupBenchmarkRecord),
        )
    }

    private fun toBackupDocument(doc: LibraryDocument) =
        BackupLibraryDocument(doc.id, doc.title, doc.text, doc.savedAtMillis)

    private fun toBackupPosition(position: DocumentPosition) =
        BackupResumePosition(
            position.documentId,
            position.engineId,
            position.voiceId,
            position.speed,
            position.sentenceIndex,
        )

    private fun toBackupFavorites(store: FavoritesStore) =
        BackupFavorites(
            voices = store.favoriteVoices(),
            models = store.favoriteModels(),
            flagged = store.flaggedModels(),
        )

    private fun toBackupBlendedVoice(spec: BlendedVoiceSpec) =
        BackupBlendedVoice(spec.id, spec.name, spec.modelId, spec.voiceAId, spec.voiceBId, spec.weight)

    private fun toBackupUsageStats(snapshot: UsageStatsSnapshot) =
        BackupUsageStats(
            totalListeningMillis = snapshot.totalListeningMillis,
            totalWordsSpoken = snapshot.totalWordsSpoken,
            generationCount = snapshot.generationCount,
            playCountByModelId = snapshot.playCountByModelId,
        )

    private fun toBackupBenchmarkRecord(record: BenchmarkRecord) =
        BackupBenchmarkRecord(record.engineId, record.device, record.timestampMillis, record.realTimeFactor)
}
