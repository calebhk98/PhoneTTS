package com.phonetts.core.engine

/**
 * Re-applies persisted voice mixes to a loaded engine and merges the results into the voice list the
 * main reader's dropdown shows (issue #42). Pure seam logic so it is unit-testable on a plain JVM and
 * shared by whatever surfaces the voice list.
 *
 * Both steps are fail-soft and SSOT-clean:
 *  - [apply] detects blend capability via `engine is BlendableVoices` (polymorphism, not a banned
 *    `when(modelType)` - rule 5); a non-blendable engine, or a spec whose source voices are missing,
 *    simply yields nothing. The caller gates on [com.phonetts.core.model.ModelDescriptor.supportsVoiceBlend]
 *    so nothing is even attempted for a model that can't blend.
 *  - [merge] keeps the descriptor's own voices (SSOT) first and appends only blended voices whose id
 *    isn't already present, so a mix is selectable alongside the built-in voices without duplicating one.
 */
object BlendedVoiceCatalog {
    /**
     * Register each spec in [specs] on [engine] if it can blend, returning the voices that were
     * created. A spec the engine rejects (unknown source voice) is skipped, never fatal.
     */
    fun apply(
        engine: VoiceEngine?,
        specs: List<BlendedVoiceSpec>,
    ): List<Voice> {
        val blendable = engine as? BlendableVoices ?: return emptyList()
        return specs.mapNotNull(blendable::addBlendedVoice)
    }

    /**
     * The dropdown's voice list: [base] (a descriptor's own voices) followed by every voice in
     * [blended] whose id isn't already a base voice, so the merge never duplicates or shadows a
     * built-in voice.
     */
    fun merge(
        base: List<Voice>,
        blended: List<Voice>,
    ): List<Voice> {
        val baseIds = base.mapTo(HashSet()) { it.id }
        return base + blended.filterNot { it.id in baseIds }
    }
}
