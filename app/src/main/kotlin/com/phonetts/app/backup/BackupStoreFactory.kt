package com.phonetts.app.backup

import android.content.Context
import com.phonetts.app.PrefsPreferenceStore
import com.phonetts.app.store.FilesDurableStore
import com.phonetts.core.backup.BackupStores
import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.metrics.UsageStatsStore
import com.phonetts.core.prefs.BlendedVoiceStore
import com.phonetts.core.prefs.DocumentLibrary
import com.phonetts.core.prefs.DocumentMemory
import com.phonetts.core.session.SessionStateStore
import com.phonetts.core.store.FavoritesStore
import java.io.File

/**
 * Builds a fresh [BackupStores] straight from [Context] (issue #149), pointing at the EXACT same
 * on-disk `SharedPreferences`/durable-document files [com.phonetts.app.AppGraph] already uses -
 * `PrefsPreferenceStore`'s own prefs file, and `filesDir/[FilesDurableStore.DIRECTORY_NAME]` - so a
 * backup reads/writes the same data the rest of the app does, without constructing a whole
 * [com.phonetts.app.AppGraph] (which also spins up the engine registry, HF downloader, etc. - none
 * of which backup needs). This mirrors the same "a seam straight from Context, no full graph" pattern
 * `AppGraph.durableErrorLogFor` already uses for the crash log, for the identical reason.
 *
 * NOTE for whoever next touches [com.phonetts.app.AppGraph]'s wiring: that class already builds every
 * one of these seven stores once (`documentLibrary`, `documentMemory`, `favoritesStore`,
 * `blendedVoices`, `sessionState`, `usageStatsStore`, `benchmarkHistory`). If `AppGraph` exposed them
 * (or this factory) as ONE shared set instead of each caller rebuilding its own thin wrapper, that
 * would remove this file's one duplicated assumption (the durable-dir path, the prefs-store
 * construction). Functionally this is equivalent either way - every store here is a stateless-except-
 * for-storage wrapper over the same on-disk files - but it is the one piece of wiring this task
 * could not change directly (see the issue's file-ownership boundaries) and is flagging instead.
 */
object BackupStoreFactory {
    fun create(context: Context): BackupStores {
        val appContext = context.applicationContext
        val preferenceStore = PrefsPreferenceStore(appContext)
        val durableStore = FilesDurableStore(File(appContext.filesDir, FilesDurableStore.DIRECTORY_NAME))
        return BackupStores(
            library = DocumentLibrary(durableStore),
            documentMemory = DocumentMemory(preferenceStore),
            favoritesStore = FavoritesStore(durableStore),
            blendedVoiceStore = BlendedVoiceStore(preferenceStore),
            sessionStateStore = SessionStateStore(durableStore),
            usageStatsStore = UsageStatsStore(durableStore),
            benchmarkHistory = BenchmarkHistory(preferenceStore),
        )
    }
}
