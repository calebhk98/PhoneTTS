package com.phonetts.app

import android.content.Context
import com.phonetts.app.hf.HfDownloader
import com.phonetts.app.hf.HttpUrlConnectionClient
import com.phonetts.app.runtime.OnnxRuntime
import com.phonetts.app.sideload.SideloadCoordinator
import com.phonetts.app.text.PassthroughPhonemizer
import com.phonetts.app.textimport.FileTextImporter
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.engine.EngineContext
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.EngineManager
import com.phonetts.core.registry.EngineRegistry
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.resolver.Resolver
import com.phonetts.core.sideload.DirectoryBundleReader
import com.phonetts.core.sideload.ModelImporter

/** Raised when no engine can identify a bundle and no manual pick is wired yet (see resolver). */
class UserPickRequiredException(bundleId: String) :
    Exception("could not identify model '$bundleId' — a manual engine pick is required")

/**
 * The app's object graph, built once in [PhoneTtsApplication]. This is the single startup wiring
 * point the reviews flagged as missing: it registers the real ONNX [OnnxRuntime], seeds the
 * [EngineRegistry] from the ServiceLoader-discovered engine modules (naming no model), and
 * assembles the resolver / catalog / importer / downloader / sideload chain over them.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val runtimeRegistry = RuntimeRegistry().apply { register(OnnxRuntime()) }
    private val phonemizer = PassthroughPhonemizer()
    private val engineContext = EngineContext(runtimeRegistry, phonemizer, appContext.filesDir.absolutePath)

    val engineRegistry = EngineRegistry().also { EngineLoader.seed(it, engineContext) }
    val engineManager = EngineManager(engineRegistry)
    val catalog = ModelCatalog()

    private val overrideStore = PrefsOverrideStore(appContext)
    private val resolver =
        Resolver(engineRegistry.list(), overrideStore) { bundle -> throw UserPickRequiredException(bundle.id) }

    val importer = ModelImporter(DirectoryBundleReader(), resolver, catalog)
    val fileTextImporter = FileTextImporter(appContext)
    val sideloadCoordinator = SideloadCoordinator(appContext, importer)
    val hfCatalog = HfCatalog(HttpUrlConnectionClient())
    val hfDownloader = HfDownloader(appContext.filesDir, importer)

    /**
     * Re-import previously downloaded/sideloaded model folders so the catalog is repopulated on
     * every launch (a saved override makes each re-resolve fast). Best-effort per folder — a
     * folder the resolver can no longer identify is skipped, not fatal.
     */
    fun hydrate() {
        val modelsDir = appContext.filesDir.resolve(ModelStorage.MODELS_DIR)
        val folders = modelsDir.listFiles()?.filter { it.isDirectory } ?: return
        folders.forEach { folder -> runCatching { importer.import(folder.absolutePath) } }
    }
}
