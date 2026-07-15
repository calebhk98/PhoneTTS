package com.phonetts.core.engine

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
