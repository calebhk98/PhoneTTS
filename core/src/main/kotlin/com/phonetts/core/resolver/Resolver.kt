package com.phonetts.core.resolver

import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor

/**
 * The single place a model-to-engine decision is made (spec §5.6, §6.2, §11.3).
 *
 * Resolution order for a bundle:
 *  1. A saved [OverrideStore] decision, if one exists - re-detection is skipped entirely and
 *     the owning engine's [VoiceEngine.forcedMatch] is trusted.
 *  2. Confident auto-detection: the first engine whose [VoiceEngine.inspect] claims the bundle.
 *  3. [userPicksEngine] as a fail-closed fallback when no engine claims the bundle, followed by
 *     that engine's [VoiceEngine.forcedMatch].
 *
 * Every resolution (auto-detected or user-picked) is persisted so future resolves for the same
 * bundle take the saved-override path.
 *
 * [engines] is a plain source of the currently available engines rather than a dependency on
 * `EngineRegistry`, keeping this seam independently testable; integration wiring happens where
 * the resolver is constructed.
 */
class Resolver(
    private val engines: List<VoiceEngine>,
    private val overrideStore: OverrideStore,
    private val userPicksEngine: (ModelBundle) -> String,
) {
    fun resolve(bundle: ModelBundle): ModelDescriptor {
        // A saved override is honored only if its engine is still registered. If that engine was
        // removed, the stale mapping is ignored and we re-detect / fall to the user pick, rather
        // than crashing (spec rule #6: removing an engine leaves no dangling reference).
        val savedEngine = overrideStore.get(bundle.id)?.let { id -> engines.firstOrNull { it.id == id } }
        if (savedEngine != null) {
            return savedEngine.forcedMatch(bundle).descriptor
        }

        val match = autoDetect(bundle) ?: forcedMatchFromUserPick(bundle)
        overrideStore.put(bundle.id, match.engineId)
        return match.descriptor
    }

    /**
     * Every registered engine the "pick an engine" UI can offer for an unresolved bundle, reduced
     * to just [SelectableEngine.id]/[SelectableEngine.displayName] - nothing else in [VoiceEngine]
     * (inspect/forcedMatch/load/...) belongs in front of a UI.
     */
    fun selectableEngines(): List<SelectableEngine> = engines.map { SelectableEngine(it.id, it.displayName) }

    /**
     * Resolve [bundle] using the engine the user explicitly chose ([engineId]), bypassing
     * auto-detection and [userPicksEngine] entirely - this is what actually makes the fail-closed
     * fallback usable end to end, rather than only describing itself. Persists the choice exactly
     * like [resolve] does, so a later resolve of the same bundle reads the saved override and skips
     * re-detection.
     *
     * Throws if [engineId] isn't currently registered, or if the chosen engine's
     * [VoiceEngine.forcedMatch] itself rejects the bundle (e.g. it's missing a file that family
     * structurally requires) - the caller (the manual-pick UI) is expected to surface that message
     * rather than silently failing.
     */
    fun resolveWithChosenEngine(
        bundle: ModelBundle,
        engineId: String,
    ): ModelDescriptor {
        val match = engineById(engineId).forcedMatch(bundle)
        overrideStore.put(bundle.id, match.engineId)
        return match.descriptor
    }

    private fun autoDetect(bundle: ModelBundle): EngineMatch? = engines.firstNotNullOfOrNull { it.inspect(bundle) }

    private fun forcedMatchFromUserPick(bundle: ModelBundle): EngineMatch {
        val chosenEngineId = userPicksEngine(bundle)
        return engineById(chosenEngineId).forcedMatch(bundle)
    }

    private fun engineById(engineId: String): VoiceEngine =
        engines.firstOrNull { it.id == engineId }
            ?: error("No engine registered with id '$engineId'")
}

/** One engine a manual "pick an engine" UI can offer - id to act on, display name to show. */
data class SelectableEngine(val id: String, val displayName: String)
