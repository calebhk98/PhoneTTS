package com.phonetts.core.backup

import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.metrics.UsageStatsStore
import com.phonetts.core.prefs.BlendedVoiceStore
import com.phonetts.core.prefs.DocumentLibrary
import com.phonetts.core.prefs.DocumentMemory
import com.phonetts.core.prefs.InMemoryPreferenceStore
import com.phonetts.core.session.SessionStateStore
import com.phonetts.core.store.FavoritesStore
import com.phonetts.core.store.InMemoryDurableStore

/** A fresh, empty [BackupStores] backed entirely by the plain-JVM in-memory doubles - shared by every
 * backup seam test in this package so each test starts from a known, isolated state. */
internal fun freshStores(): BackupStores {
    val durable = InMemoryDurableStore()
    val prefs = InMemoryPreferenceStore()
    return BackupStores(
        library = DocumentLibrary(durable),
        documentMemory = DocumentMemory(prefs),
        favoritesStore = FavoritesStore(durable),
        blendedVoiceStore = BlendedVoiceStore(prefs),
        sessionStateStore = SessionStateStore(durable),
        usageStatsStore = UsageStatsStore(durable),
        benchmarkHistory = BenchmarkHistory(prefs),
    )
}
