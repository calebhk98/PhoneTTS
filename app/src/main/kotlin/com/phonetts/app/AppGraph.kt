package com.phonetts.app

import android.content.Context
import com.phonetts.app.audio.export.ExportFormats
import com.phonetts.app.hf.HfDownloader
import com.phonetts.app.hf.HttpUrlConnectionClient
import com.phonetts.app.runtime.LlamaCppSpeechTokenRuntime
import com.phonetts.app.runtime.OnnxRuntime
import com.phonetts.app.sideload.SideloadCoordinator
import com.phonetts.app.text.EspeakPhonemizer
import com.phonetts.app.textimport.FileTextImporter
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.engine.EngineContext
import com.phonetts.core.prefs.DocumentMemory
import com.phonetts.core.prefs.FavoriteVoices
import com.phonetts.core.resolver.DetectionFailureExplainer
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.EngineManager
import com.phonetts.core.registry.EngineRegistry
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.registry.ModelManager
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

    // Both pluggable backends live in one registry (spec §5.3): the ONNX Runtime the Tier-A/B
    // engines use, and the non-ONNX llama.cpp/ggml SpeechTokenRuntime for CosyVoice2's LLM stage.
    // The latter reports isAvailable()=false unless the app was built with -PwithCosyVoice=true, in
    // which case CosyVoice2 simply isn't offered — registration itself is harmless and unconditional.
    val runtimeRegistry =
        RuntimeRegistry().apply {
            register(OnnxRuntime())
            register(LlamaCppSpeechTokenRuntime())
        }

    // EspeakPhonemizer never throws (see its kdoc): it falls back to PassthroughPhonemizer
    // internally and logs a warning if the native lib/data files aren't present on this device
    // or this build (docs/espeak-ng-integration.md), so no try/catch is needed here.
    private val phonemizer = EspeakPhonemizer(appContext)
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

    // Manage (list sizes / delete) downloaded models. File I/O is injected so :core stays pure;
    // deleting the loaded model unloads it first and forgets its saved override (PrefsOverrideStore
    // is clearable), keeping the catalog, memory, and override store consistent.
    val modelManager =
        ModelManager(
            catalog = catalog,
            dirSizeBytes = { modelId -> ModelStorage.sizeBytes(appContext.filesDir, modelId) },
            deleteModelDir = { modelId -> ModelStorage.delete(appContext.filesDir, modelId) },
            overrideStore = overrideStore,
            engineManager = engineManager,
        )

    // Preferences-backed features: favorite voices + per-language default, per-document resume,
    // and the read-only "why did detection fail" explainer. All model facts still come from
    // descriptor.voices / the registry — these only persist user choices.
    private val preferenceStore = PrefsPreferenceStore(appContext)
    val favoriteVoices = FavoriteVoices(preferenceStore)
    val documentMemory = DocumentMemory(preferenceStore)
    val detectionFailureExplainer = DetectionFailureExplainer()

    // The export-format registry (WAV always; AAC always; Opus on API 29+). The picker reads
    // display names/extensions/MIME from here — no format string is hardcoded in the UI (SSOT).
    val exportFormats = ExportFormats.available(appContext)

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
