package com.phonetts.core.backup

import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.metrics.UsageStatsStore
import com.phonetts.core.prefs.BlendedVoiceStore
import com.phonetts.core.prefs.DocumentLibrary
import com.phonetts.core.prefs.DocumentMemory
import com.phonetts.core.session.SessionStateStore
import com.phonetts.core.store.FavoritesStore

/**
 * The existing `:core` stores a backup reads from (export) and writes to (restore) - issue #149.
 * Bundled into one class so [BackupExporter.export] / [BackupRestorer.restore] stay under detekt's
 * function-parameter-count limit, mirroring how `SettingsUpdateInfo`/`SettingsUpdateActions` already
 * bundle related Settings state elsewhere in this codebase. Every field is a store this app already
 * has for an unrelated reason (issue #139, #113/#119, #42, session state, #130, #39) - backup adds no
 * new storage of its own, only a way to read/write all of them together through their own public APIs.
 */
data class BackupStores(
    val library: DocumentLibrary,
    val documentMemory: DocumentMemory,
    val favoritesStore: FavoritesStore,
    val blendedVoiceStore: BlendedVoiceStore,
    val sessionStateStore: SessionStateStore,
    val usageStatsStore: UsageStatsStore,
    val benchmarkHistory: BenchmarkHistory,
)
