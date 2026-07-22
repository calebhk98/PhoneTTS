package com.phonetts.core.engine

import kotlin.reflect.KClass

/**
 * The model input produced by a [TextFrontend]: the token/phoneme id sequence the model
 * consumes, plus any model-specific side inputs (e.g. MeloTTS BERT prosody features, a
 * speaker embedding). Kept generic because this is where models diverge hardest.
 *
 * Not a data class: [tokenIds] is a primitive array, whose structural equality would be
 * misleading. Identity equality is the correct default here.
 */
class ModelInput(
    val tokenIds: LongArray,
    val extras: Map<String, Any> = emptyMap(),
)

/**
 * A typed key into [ModelInput.extras] (issue #18 item 2): pairs the string key a [TextFrontend]
 * stores an extra under with the Kotlin type an engine expects to read it back as. Lets a single
 * generic accessor (`ModelInput.extra`/`requireExtra` in `com.phonetts.engines.common`) fetch and
 * type-check any extra — a `LongArray` of tone ids, a scalar `Int`, a `FloatArray` embedding, or
 * whatever a future frontend adds — with no per-model helper code and no unsafe `as?` cast at the
 * callsite. Adding a new kind of extra means declaring a new [ExtraKey], never touching the
 * accessor.
 *
 * Lives here, next to [ModelInput], because it is the data shape `extras` is keyed by — a core
 * fact, not a leaf helper (the accessor functions that use it are the leaf helper, and live in
 * `:engines:common`).
 */
data class ExtraKey<T : Any>(
    val name: String,
    val type: KClass<T>,
) {
    companion object {
        /** Builds a key for [T], inferring [type] from the reified call site. */
        inline fun <reified T : Any> of(name: String): ExtraKey<T> = ExtraKey(name, T::class)
    }
}

/**
 * Turns text into whatever a model eats (spec §5.2). This varies hardest between models,
 * so it is deliberately NOT part of [VoiceEngine]: it lives inside each engine and the app
 * never sees it.
 *
 *  - Piper / KittenTTS / Kokoro -> a shared espeak-ng-backed frontend.
 *  - MeloTTS -> its own frontend (BERT prosody step, language-specific tokenizer).
 *  - CosyVoice2 -> a token-based frontend.
 */
interface TextFrontend {
    fun toModelInput(
        text: String,
        language: String,
    ): ModelInput
}
