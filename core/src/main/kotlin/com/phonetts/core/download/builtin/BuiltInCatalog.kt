package com.phonetts.core.download.builtin

// The curated set offered as one-tap "recommended" downloads, so a first-run user gets a working
// model immediately instead of having to search Hugging Face. Only models that are BOTH proven to
// produce valid audio (docs/MODEL-VERIFICATION.md) AND handled end-to-end by an engine's
// inspect()/load() belong here — a one-tap download must not land a model that then fails to load.
//
// MeloTTS is intentionally absent: it runs shape-correctly but doesn't yet produce real speech
// (its symbol table is export-specific — see docs/MODEL-VERIFICATION.md), so a one-tap download
// would land a silent model. It joins once it sings.
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

    // The fp32 export (the q8f16 variant segfaults ONNX Runtime — docs/MODEL-VERIFICATION.md).
    // Voices keep their voices/<name>.bin path so KokoroEngine.inspect() fingerprints them; a
    // curated subset of the repo's 54 voices keeps the download reasonable while still multi-voice.
    val KOKORO_82M =
        BuiltInModel(
            id = "kokoro-82m",
            displayName = "Kokoro-82M (English, multi-voice)",
            repoId = "onnx-community/Kokoro-82M-v1.0-ONNX",
            approxSizeMb = 326,
            files =
                listOf(
                    BuiltInFile("onnx/model.onnx", "model.onnx"),
                    BuiltInFile("config.json", "config.json"),
                    BuiltInFile("voices/af_heart.bin", "voices/af_heart.bin"),
                    BuiltInFile("voices/af_bella.bin", "voices/af_bella.bin"),
                    BuiltInFile("voices/am_michael.bin", "voices/am_michael.bin"),
                    BuiltInFile("voices/bf_emma.bin", "voices/bf_emma.bin"),
                    BuiltInFile("voices/bm_george.bin", "voices/bm_george.bin"),
                ),
            note = "Highest quality, 5 English voices. Larger download (fp32).",
        )

    /** Every recommended model, in display order (smallest-first is friendlier, but quality-first here). */
    val ALL: List<BuiltInModel> = listOf(PIPER_LESSAC, KITTEN_NANO, KOKORO_82M)
}
