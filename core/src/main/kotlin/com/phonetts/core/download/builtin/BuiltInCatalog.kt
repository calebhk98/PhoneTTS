package com.phonetts.core.download.builtin

// The curated set offered as one-tap "recommended" downloads, so a first-run user gets a working
// model immediately instead of having to search Hugging Face. Only models that are BOTH proven to
// produce valid audio (docs/MODEL-VERIFICATION.md) AND handled end-to-end by an engine's
// inspect()/load() belong here — a one-tap download must not land a model that then fails to load.
// [ALL] stays deliberately small (one per engine family). The much larger set of every voice
// rhasspy/piper-voices publishes is NOT listed here as static data — it's fetched and parsed at
// runtime from upstream's own `voices.json` (see [PiperVoicesIndex]) so the browsable Piper list
// is always current with upstream instead of a hand-maintained snapshot of it.
object BuiltInCatalog {
    // The one legitimately hand-curated Piper voice: a known-good "first download" pick, not a
    // stand-in for the full voice list (that's fetched dynamically — see [PiperVoicesIndex]).
    // repoId is sourced from PiperVoicesIndex.REPO_ID (SSOT: the one place that literal lives),
    // never re-typed here.
    val PIPER_LESSAC =
        BuiltInModel(
            id = "piper-en_US-lessac-medium",
            displayName = "Piper — Lessac (English, United States, medium)",
            repoId = PiperVoicesIndex.REPO_ID,
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
                    BuiltInFile("tokenizer.json", "tokenizer.json"),
                    BuiltInFile("voices/af_heart.bin", "voices/af_heart.bin"),
                    BuiltInFile("voices/af_bella.bin", "voices/af_bella.bin"),
                    BuiltInFile("voices/am_michael.bin", "voices/am_michael.bin"),
                    BuiltInFile("voices/bf_emma.bin", "voices/bf_emma.bin"),
                    BuiltInFile("voices/bm_george.bin", "voices/bm_george.bin"),
                ),
            note = "Highest quality, 5 English voices. Larger download (fp32).",
        )

    // MiaoMint/MeloTTS-ONNX's sherpa-onnx English export (onnx_exports/en_v2) — PROVEN to produce
    // real, non-silent speech by scripts/model-verify/run_melo2.py (10.54s, 908KB, peak 0.293).
    // Ships its own symbol table (tokens.txt) and G2P dictionary (lexicon.txt), which is what lets
    // MeloEngine avoid a hardcoded phoneme map (SSOT). Replaces the abandoned seasonstudio export,
    // which ran shape-correctly but produced silence (docs/MODEL-VERIFICATION.md).
    val MELO_EN =
        BuiltInModel(
            id = "melotts-en-v2",
            displayName = "MeloTTS — English v2",
            repoId = "MiaoMint/MeloTTS-ONNX",
            approxSizeMb = 175,
            files =
                listOf(
                    BuiltInFile(repoPath = "onnx_exports/en_v2/model.onnx", localName = "model.onnx"),
                    BuiltInFile(repoPath = "onnx_exports/en_v2/tokens.txt", localName = "tokens.txt"),
                    BuiltInFile(repoPath = "onnx_exports/en_v2/lexicon.txt", localName = "lexicon.txt"),
                    BuiltInFile(repoPath = "onnx_exports/en_v2/metadata.json", localName = "metadata.json"),
                ),
            note = "Multi-speaker English VITS model.",
        )

    // CosyVoice3-0.5B, the Tier-C autoregressive model — the deployable GGUF sibling of the
    // CosyVoice2 model proven in PyTorch (docs/MODEL-VERIFICATION.md). It runs ONLY on the native
    // ggml backend built with -PwithCosyVoice, so it declares requiresRuntimeId = the CosyVoice
    // runtime id: in a standard APK that runtime is unavailable and the recommended list hides this
    // entry (no broken one-tap); in a native build it appears as the 5th model. The minimal 745 MB
    // combo (Q4_K LLM + Q8_0 flow + F16 HiFT + voices) is the four-GGUF set CosyVoice2Engine
    // fingerprints by name — the repo already ships them under those exact stage-prefixed names.
    val COSYVOICE3_05B =
        BuiltInModel(
            id = "cosyvoice3-0.5b",
            displayName = "CosyVoice3-0.5B (multilingual, native)",
            repoId = "cstr/cosyvoice3-0.5b-2512-GGUF",
            approxSizeMb = 745,
            files =
                listOf(
                    BuiltInFile("cosyvoice3-llm-q4_k.gguf", "cosyvoice3-llm-q4_k.gguf"),
                    BuiltInFile("cosyvoice3-flow-q8_0.gguf", "cosyvoice3-flow-q8_0.gguf"),
                    BuiltInFile("cosyvoice3-hift-f16.gguf", "cosyvoice3-hift-f16.gguf"),
                    BuiltInFile("cosyvoice3-voices.gguf", "cosyvoice3-voices.gguf"),
                ),
            note = "Highest quality, multilingual. Needs the native (-PwithCosyVoice) build; large download.",
            requiresRuntimeId = "cosyvoice",
        )

    /** Every recommended model, in display order (smallest-first is friendlier, but quality-first here). */
    val ALL: List<BuiltInModel> = listOf(PIPER_LESSAC, KITTEN_NANO, KOKORO_82M, MELO_EN, COSYVOICE3_05B)
}
