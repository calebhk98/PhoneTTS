package com.phonetts.core.download.builtin

// The curated set offered as one-tap "recommended" downloads, so a first-run user gets a working
// model immediately instead of having to search Hugging Face. Only models that are BOTH proven to
// produce valid audio (docs/MODEL-VERIFICATION.md) AND handled end-to-end by an engine's
// inspect()/load() belong here — a one-tap download must not land a model that then fails to load.
//
// Kokoro and MeloTTS are intentionally absent for now: Kokoro's engine still expects a voices.json
// table while the real repo ships voices/*.bin, and MeloTTS's frontend rework is in progress — both
// tracked in docs/research/onnx-io.md and docs/MODEL-VERIFICATION.md. Adding them here is a one-line
// change once their engines load the real repos.
object BuiltInCatalog {
    val PIPER_LESSAC =
        BuiltInModel(
            id = "piper-en_US-lessac-medium",
            displayName = "Piper — Lessac (English, medium)",
            repoId = "rhasspy/piper-voices",
            approxSizeMb = 63,
            files =
                listOf(
                    BuiltInFile(
                        repoPath = "en/en_US/lessac/medium/en_US-lessac-medium.onnx",
                        localName = "en_US-lessac-medium.onnx",
                    ),
                    BuiltInFile(
                        repoPath = "en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
                        localName = "en_US-lessac-medium.onnx.json",
                    ),
                ),
            note = "Fast, clear English voice. Recommended first download.",
        )

    val KITTEN_NANO =
        BuiltInModel(
            id = "kittentts-nano-0.1",
            displayName = "KittenTTS — Nano (English)",
            repoId = "KittenML/kitten-tts-nano-0.1",
            approxSizeMb = 24,
            files =
                listOf(
                    BuiltInFile("kitten_tts_nano_v0_1.onnx", "kitten_tts_nano_v0_1.onnx"),
                    BuiltInFile("config.json", "config.json"),
                    BuiltInFile("voices.npz", "voices.npz"),
                ),
            note = "Tiny 8-voice English model. Smallest download.",
        )

    /** Every recommended model, in display order. */
    val ALL: List<BuiltInModel> = listOf(PIPER_LESSAC, KITTEN_NANO)
}
