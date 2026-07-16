package com.phonetts.core.engine

import com.phonetts.core.model.ModelDescriptor

/**
 * The result of an engine claiming a bundle. Carries the [engineId] to persist as the
 * model-to-engine decision (spec §5.6) and the fully-built [descriptor] that the resolver
 * returns and the UI renders from.
 *
 * Returned by [VoiceEngine.inspect] on a confident auto-detect, and by
 * [VoiceEngine.forcedMatch] when the user manually assigns this engine to a bundle.
 */
data class EngineMatch(
    val engineId: String,
    val descriptor: ModelDescriptor,
)
