package com.phonetts.app

import android.content.Context
import com.phonetts.app.audio.export.ExportFormats
import com.phonetts.app.device.DeviceInfo
import com.phonetts.app.hf.HfDownloader
import com.phonetts.app.hf.HttpUrlConnectionClient
import com.phonetts.app.runtime.NativeCosyVoiceRuntime
import com.phonetts.app.runtime.OnnxRuntime
import com.phonetts.app.sideload.SideloadCoordinator
import com.phonetts.app.text.EspeakPhonemizer
import com.phonetts.app.textimport.FileTextImporter
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.PlatformServices
import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.prefs.AppThemePreference
import com.phonetts.core.prefs.BlendedVoiceStore
import com.phonetts.core.prefs.DocumentLibrary
import com.phonetts.core.prefs.DocumentMemory
import com.phonetts.core.prefs.FavoriteVoices
import com.phonetts.core.prefs.LastUsedSelectionStore
import com.phonetts.core.prefs.LongDocumentPreferences
import com.phonetts.core.prefs.OnboardingState
import com.phonetts.core.prefs.ReadingTextPreferences
import com.phonetts.core.prefs.ResourceUsageStore
import com.phonetts.core.prefs.StorageLocationPreference
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
import com.phonetts.core.storage.ModelStorageMigrator
import com.phonetts.core.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Raised when no engine can identify a bundle and no manual pick is wired yet (see resolver).
 * [explanation] is [com.phonetts.core.resolver.DetectionFailureExplainer]'s narration of WHY —
 * which engines were asked, which (if any) companion files the bundle has — so the UI can surface
 * it to the user instead of a bare "could not identify" (issue #19-2).
 */
class UserPickRequiredException(bundleId: String, val explanation: String) :
    Exception("could not identify model '$bundleId' — a manual engine pick is required")

/**
 * :app's [PlatformServices] implementation: the one Android-shaped fact ([appContext]) that a
 * discovered [com.phonetts.core.engine.EngineProvider] may need but that :core is forbidden from
 * knowing about (e.g. `SystemTtsEngineProvider`, which wraps an installed OS `TextToSpeech`
 * service rather than loading weights of its own). :core only ever sees the neutral marker
 * interface via [EngineContext.platform]; a provider that needs Android casts to this concrete
 * type itself.
 */
class AppPlatformServices(val appContext: Context) : PlatformServices

/**
 * The app's object graph, built once in [PhoneTtsApplication]. This is the single startup wiring
 * point the reviews flagged as missing: it registers the real ONNX [OnnxRuntime], seeds the
 * [EngineRegistry] from the ServiceLoader-discovered engine modules (naming no model), and
 * assembles the resolver / catalog / importer / downloader / sideload chain over them.
 */
class AppGraph(context: Context) {
    // Public: the generation/download notifiers (built in TtsViewModel / MainActivity) need the
    // application Context, and there is no reason to hide the already-app-scoped context from them.
    val appContext = context.applicationContext

    // Both pluggable backends live in one registry (spec §5.3): the ONNX Runtime the Tier-A/B
    // engines use, and the non-ONNX ggml NativeTtsRuntime that runs CosyVoice3's whole text→audio
    // pipeline. The latter reports isAvailable()=false unless the app was built with
    // -PwithCosyVoice=true, in which case CosyVoice simply isn't offered — registration itself is
    // harmless and unconditional.
    val runtimeRegistry =
        RuntimeRegistry().apply {
            register(OnnxRuntime())
            register(NativeCosyVoiceRuntime())
        }

    // EspeakPhonemizer never throws (see its kdoc): it falls back to PassthroughPhonemizer
    // internally and logs a warning if the native lib/data files aren't present on this device
    // or this build (docs/espeak-ng-integration.md), so no try/catch is needed here.
    private val phonemizer = EspeakPhonemizer(appContext)

    // The [EngineContext] every discovered [com.phonetts.core.engine.EngineProvider] is built
    // from. [platform] is :app's own [PlatformServices] impl, exposing the application Context
    // to whichever provider's engine needs one — e.g. an engine wrapping an installed OS service
    // rather than loading weights of its own (`SystemTtsEngineProvider`). :core never sees the
    // concrete type, only the neutral marker (rule: :core stays Android-free).
    private val engineContext = EngineContext(runtimeRegistry, phonemizer, platform = AppPlatformServices(appContext))

    // Discovered once and reused both to seed the registry (below) and to pull each provider's
    // always-available descriptors in [registerBuiltInDescriptors] — the SAME ServiceLoader path
    // every engine (built-in or sideloadable) goes through. No engine is named here (rule 5):
    // adding/removing one is adding/removing a module + its META-INF/services entry, nothing else.
    private val discoveredProviders = EngineLoader.discoverProviders()

    val engineRegistry =
        EngineRegistry().also { registry -> EngineLoader.seed(registry, engineContext, discoveredProviders) }
    val engineManager = EngineManager(engineRegistry)
    val catalog = ModelCatalog()

    // A small dedicated scope for the one-shot system-TTS discovery [hydrate] kicks off below.
    // Deliberately NOT run inline/synchronously the way hydrate()'s own folder scan is: Android
    // delivers TextToSpeech's init callback back through this process's main-thread Looper no
    // matter which thread constructed it, so blocking any thread on a latch waiting for that
    // callback risks starving the very Looper that would deliver it. Launching a coroutine instead
    // never blocks a thread — the catalog picks up the discovered engines within about a second of
    // startup, via the same catalog.list() re-reads the UI already does on every model-list refresh.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Read-only "why did detection fail" narration (issue #19-2): explain() only calls the same
    // VoiceEngine.inspect() probe the resolver already used and changes no state, so it is safe to
    // invoke right here, on the failure path, before surfacing a [UserPickRequiredException].
    val detectionFailureExplainer = DetectionFailureExplainer()

    private val overrideStore = PrefsOverrideStore(appContext)
    private val resolver =
        Resolver(engineRegistry.list(), overrideStore) { bundle ->
            val report = detectionFailureExplainer.explain(bundle, engineRegistry.list())
            throw UserPickRequiredException(bundle.id, report.summary)
        }

    // Backs every preferences-derived feature below, including where models are stored (issue
    // #4/#5) — declared early because [modelsBaseDir] (and anything built from it) needs it ready.
    private val preferenceStore = PrefsPreferenceStore(appContext)

    // App-private storage by default; a user-picked folder (internal or SD card) once one is
    // chosen on the Manage screen, so downloaded weights SURVIVE an uninstall/reinstall (issue
    // #4/#5). Read fresh on every call rather than cached, so switching it takes effect immediately
    // for anything that calls this instead of capturing a fixed File once.
    val storageLocationPreference = StorageLocationPreference(preferenceStore)

    /** Where model folders currently live: the app-private default, or the user's chosen folder. */
    fun modelsBaseDir(): java.io.File =
        storageLocationPreference.customBasePath()?.let { java.io.File(it) } ?: appContext.filesDir

    val importer = ModelImporter(DirectoryBundleReader(), resolver, catalog)
    val fileTextImporter = FileTextImporter(appContext)
    val sideloadCoordinator = SideloadCoordinator(appContext, importer, ::modelsBaseDir)
    val hfCatalog = HfCatalog(HttpUrlConnectionClient())

    // A `var`, not a `val`: HfDownloader captures its base dir at construction (its own File field
    // is fixed — that class is owned elsewhere and deliberately untouched here), so relocating
    // storage (issue #4/#5) rebuilds it via [relocateStorage] rather than mutating it in place.
    var hfDownloader: HfDownloader = HfDownloader(modelsBaseDir(), importer)
        private set

    // Checks GitHub Releases for a newer APK than the running build (BuildConfig.VERSION_NAME) and
    // only ever OFFERS it — never force-updates (the UI shows a dismissible banner). Fail-closed.
    val updateChecker = UpdateChecker(HttpUrlConnectionClient())

    // Manage (list sizes / delete) downloaded models. File I/O is injected so :core stays pure;
    // deleting the loaded model unloads it first and forgets its saved override (PrefsOverrideStore
    // is clearable), keeping the catalog, memory, and override store consistent. Also owns the
    // storage-location switch (issue #4/#5): [relocateStorage] MIGRATES already-downloaded models
    // from the old base dir to the new one (the data-loss bug this fixes — see its own kdoc),
    // rebuilds [hfDownloader], and re-scans the newly chosen folder.
    val modelManager =
        ModelManager(
            catalog = catalog,
            dirSizeBytes = { modelId -> ModelStorage.sizeBytes(modelsBaseDir(), modelId) },
            deleteModelDir = { modelId -> ModelStorage.delete(modelsBaseDir(), modelId) },
            overrideStore = overrideStore,
            engineManager = engineManager,
            storageLocation = storageLocationPreference,
            onStorageLocationChanged = ::relocateStorage,
        )

    // Runs when the user picks a new storage location or resets to the default (issue #4/#5,
    // data-loss bug fix). [oldPath]/[newPath] are the raw custom-base-path values from BEFORE and
    // AFTER [ModelManager.changeStorageLocation] just persisted the change (null means "app-private
    // default" on either side) — passed explicitly rather than re-read, because by the time this
    // runs the preference already holds the NEW value only.
    //
    // Order matters: migrate the physical model folders from old → new FIRST (never lose a
    // downloaded model), THEN unload/clear/rescan — otherwise the old catalog is dropped and the
    // new one built from a location the files were never moved to, which is exactly how models
    // used to go missing (and worse, stayed reported as "installed" while being neither usable nor
    // deletable). Re-picking the SAME folder (rule 2) resolves as [ModelStorageMigrator.sameLocation]
    // and returns immediately, touching nothing — no unload, no clear, no rescan.
    private fun relocateStorage(
        oldPath: String?,
        newPath: String?,
    ): String? {
        val oldBaseDir = oldPath?.let { java.io.File(it) } ?: appContext.filesDir
        val newBaseDir = newPath?.let { java.io.File(it) } ?: appContext.filesDir
        if (ModelStorageMigrator.sameLocation(oldBaseDir, newBaseDir)) return null

        val outcome =
            ModelStorageMigrator.migrate(
                oldBaseDir.resolve(ModelStorage.MODELS_DIR),
                newBaseDir.resolve(ModelStorage.MODELS_DIR),
            )

        // rule #6: one engine loaded at a time, and its weights may live under the OLD base dir.
        engineManager.unloadCurrent()
        hfDownloader = HfDownloader(modelsBaseDir(), importer)
        // The old catalog may name models that no longer resolve at the new location (or, thanks to
        // the migration above, now genuinely do) — cleared and immediately re-scanned rather than
        // trusting stale entries, exactly [hydrate]'s own job, just triggered mid-session.
        catalog.clear()
        hydrate()

        return relocationMessage(outcome)
    }

    // A partial failure is the only outcome worth surfacing: [ModelStorageMigrator] never deletes a
    // source it couldn't confirm was copied (fail safe), so nothing is lost, but the user needs to
    // know some models are still sitting at the OLD location instead of the one they just picked.
    private fun relocationMessage(outcome: ModelStorageMigrator.Outcome): String? =
        (outcome as? ModelStorageMigrator.Outcome.PartialFailure)?.let {
            "Couldn't move ${it.failedNames.joinToString(", ")} to the new folder — " +
                "left in place at the old location so nothing was lost."
        }

    // Preferences-backed features: favorite voices + per-language default, per-document resume,
    // and the GLOBAL last-used model/voice/speed (issue #19-1 — one shared "where I left off",
    // deliberately not per-document; see LastUsedSelection's kdoc). All model facts still come
    // from descriptor.voices / the registry — these only persist the user's choices.
    val favoriteVoices = FavoriteVoices(preferenceStore)
    val documentMemory = DocumentMemory(preferenceStore)
    val readingTextPreferences = ReadingTextPreferences(preferenceStore)
    val lastUsedSelection = LastUsedSelectionStore(preferenceStore)

    // Saved multi-document library (issue #19-5): titles/text only, keyed by the same content-derived
    // id DocumentMemory uses for resume positions (see TtsViewModel.documentIdFor), so opening a saved
    // document lines up with its resume point automatically.
    val documentLibrary = DocumentLibrary(preferenceStore)

    // Saved voice mixes (issue #42): only the recipe (two source voice ids + weight) is stored;
    // the blended embedding is recomputed by the engine on load, so no audio/embedding is persisted.
    val blendedVoices = BlendedVoiceStore(preferenceStore)

    // Long-document (spill-to-disk) mode (issue #34): an opt-in toggle only; the actual scratch file
    // is minted by [newSpillFile] so :core stays Android-free (it takes a plain java.io.File).
    val longDocumentPreferences = LongDocumentPreferences(preferenceStore)

    // UI-preference seams over the same store: the chosen color theme (reading/OLED schemes) and
    // the one-shot "has the first-run walkthrough been seen?" flag. Both hold only the user's
    // choice — the theme's concrete colors live in the theme layer, the walkthrough copy in its UI.
    val appThemePreference = AppThemePreference(preferenceStore)
    val onboardingState = OnboardingState(preferenceStore)

    // Resource-cost hinting (issue #38): the descriptor carries each engine's a-priori peak-RAM
    // estimate; this store refines it from peak RAM previous loads actually cost. Persisted benchmark
    // history (issue #39, off-by-default power-user view) rides the same preference store.
    val resourceUsageStore = ResourceUsageStore(preferenceStore)
    val benchmarkHistory = BenchmarkHistory(preferenceStore)

    /**
     * A fresh scratch file in app cache for a [com.phonetts.core.audio.buffer.ChunkSpill] (issue #34).
     * Lives in `cacheDir` so the OS can reclaim it under storage pressure; [ChunkSpill.close] deletes
     * it when the buffer is released.
     */
    fun newSpillFile(): java.io.File = java.io.File.createTempFile("phonetts_spill_", ".pcm", appContext.cacheDir)

    /** Device free RAM right now, in bytes — the reference point the resource-cost hints display against. */
    fun availableRamBytes(): Long = DeviceInfo.availableRamBytes(appContext)

    /** This process's current footprint, in bytes — recorded after a load to refine RAM estimates. */
    fun processMemoryBytes(): Long = DeviceInfo.processMemoryBytes()

    /** A stable name for this device, used to compare benchmark history like-for-like (issue #39). */
    val deviceName: String get() = DeviceInfo.name

    // The export-format registry (WAV always; AAC always; Opus on API 29+). The picker reads
    // display names/extensions/MIME from here — no format string is hardcoded in the UI (SSOT).
    val exportFormats = ExportFormats.available(appContext)

    /**
     * Re-import previously downloaded/sideloaded model folders so the catalog is repopulated on
     * every launch (a saved override makes each re-resolve fast). Best-effort per folder — a
     * folder the resolver can no longer identify is skipped, not fatal (though it IS recorded as an
     * [com.phonetts.core.registry.UnresolvedModel] by [importer], issue #8).
     *
     * Reads [modelsBaseDir] fresh, not a cached path, so a storage location picked in an earlier
     * session (issue #4/#5) is honored on this launch with no extra wiring.
     */
    fun hydrate() {
        val modelsDir = modelsBaseDir().resolve(ModelStorage.MODELS_DIR)
        val folders = modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        folders.forEach { folder -> runCatching { importer.import(folder.absolutePath) } }
        registerBuiltInDescriptors()
    }

    // Generic across EVERY discovered provider (rule 5 — no engine named in shared wiring): most
    // providers' [EngineProvider.builtInDescriptors] default to emptyList() and are effectively a
    // no-op here; a provider like `SystemTtsEngineProvider` that wraps an already-installed OS
    // service (issue #77) uses this hook to surface its "models" straight into [catalog], since
    // they aren't downloaded bundles the normal resolver pipeline ever sees. Best-effort and
    // non-fatal per provider: one provider's discovery failing (e.g. a device TTS query error)
    // must never break hydrate()'s (synchronous, load-bearing) bundle re-import above, and must
    // never hide another provider's descriptors.
    private fun registerBuiltInDescriptors() {
        appScope.launch {
            discoveredProviders.forEach { provider ->
                runCatching { provider.builtInDescriptors(engineContext) }
                    .getOrDefault(emptyList())
                    .forEach(catalog::add)
            }
        }
    }

    companion object {
        // Where the in-app update check looks for newer APKs (the GitHub Releases of this repo).
        const val REPO_OWNER = "calebhk98"
        const val REPO_NAME = "PhoneTTS"
    }
}
