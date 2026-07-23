package com.phonetts.core.automation

import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter

/**
 * A validated automation job, ready to hand to the ONE generation path: the resolved model
 * [descriptor], the concrete [voiceId] to speak, and the [params] bag (speed routed to the model's
 * native knob, every other declared parameter at its default). This is the single object the
 * Android activity carries from planning into `synthesize(text, voiceId, params)` - no field is
 * re-derived downstream.
 */
data class AutomationPlan(
    val descriptor: ModelDescriptor,
    val voiceId: String,
    val params: SynthesisParams,
)

/** Outcome of planning an [AutomationRequest] against the catalog - either a ready plan or a reason. */
sealed interface AutomationResult {
    data class Planned(val plan: AutomationPlan) : AutomationResult

    /** Fail-closed refusal with a human-readable [reason] the caller reports back in its result intent. */
    data class Invalid(val reason: String) : AutomationResult
}

/**
 * Turns a typed [AutomationRequest] plus the current catalog into an [AutomationResult]. Pure and
 * deterministic (no Android, no I/O) so the whole resolve/validate seam is unit-tested in `:core`.
 *
 * This adds NO synthesis logic - it only picks the descriptor/voice/params the existing
 * `synthesize()` flow already consumes. Fail-closed throughout (CLAUDE.md rule 4): a missing field,
 * an unknown engine, or an unknown voice yields [AutomationResult.Invalid] rather than a guess.
 */
object AutomationPlanner {
    /**
     * Resolve [request] against the models in [catalog] (typically `ModelCatalog.list()`).
     * Guard-claused (never-nesting): each precondition returns an [AutomationResult.Invalid] early.
     */
    fun plan(
        request: AutomationRequest,
        catalog: List<ModelDescriptor>,
    ): AutomationResult {
        if (request.text.isBlank()) return AutomationResult.Invalid("missing 'text' extra")
        if (request.outputUri.isNullOrBlank()) return AutomationResult.Invalid("missing 'outputUri' extra")

        val descriptor =
            selectDescriptor(request.engineId, catalog) ?: return descriptorFailure(request.engineId, catalog)
        val voiceId =
            resolveVoice(request.voiceId, descriptor) ?: return voiceFailure(request.voiceId, descriptor)
        val params = buildParams(request.speed, descriptor)
        return AutomationResult.Planned(AutomationPlan(descriptor, voiceId, params))
    }

    // Pick the model to speak with: the first descriptor for the requested engine, or - when no
    // engine was named - the first model in the catalog. Null on no match; caller reports why.
    private fun selectDescriptor(
        engineId: String?,
        catalog: List<ModelDescriptor>,
    ): ModelDescriptor? {
        if (engineId == null) return catalog.firstOrNull()
        return catalog.firstOrNull { it.engineId == engineId }
    }

    private fun descriptorFailure(
        engineId: String?,
        catalog: List<ModelDescriptor>,
    ): AutomationResult.Invalid {
        if (catalog.isEmpty()) return AutomationResult.Invalid("no models are installed")
        if (engineId == null) return AutomationResult.Invalid("no models are installed")
        return AutomationResult.Invalid("no model found for engineId '$engineId'")
    }

    // Honor an explicit voice only if the model actually has it (fail-closed); else the model default.
    private fun resolveVoice(
        voiceId: String?,
        descriptor: ModelDescriptor,
    ): String? {
        if (voiceId == null) return descriptor.defaultVoiceId
        return descriptor.voices.firstOrNull { it.id == voiceId }?.id
    }

    private fun voiceFailure(
        voiceId: String?,
        descriptor: ModelDescriptor,
    ): AutomationResult.Invalid =
        AutomationResult.Invalid("voiceId '$voiceId' is not a voice of ${descriptor.displayName}")

    // Start from every declared parameter's default, then route the requested speed to the model's
    // native speed knob (coerced into its advertised range). A model with no speed knob ignores
    // speed entirely - output is NEVER resampled to fake speed (CLAUDE.md rule 2).
    private fun buildParams(
        speed: Float?,
        descriptor: ModelDescriptor,
    ): SynthesisParams {
        val values = descriptor.parameters.associate { it.id to it.default }.toMutableMap()
        val speedParam = descriptor.speedParameter
        if (speed != null && speedParam?.range != null) {
            values[ModelParameter.SPEED_ID] = speed.coerceIn(speedParam.range)
        }
        return SynthesisParams(values)
    }
}
