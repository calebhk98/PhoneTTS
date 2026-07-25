package com.phonetts.core.download.builtin

// The curated set offered as one-tap "recommended" downloads, so a first-run user gets a working
// model immediately instead of having to search Hugging Face. Only models that are BOTH proven to
// produce valid audio (docs/MODEL-VERIFICATION.md) AND handled end-to-end by an engine's
// inspect()/load() belong here - a one-tap download must not land a model that then fails to load.
// [ALL] stays deliberately small (one per engine family). The much larger set of every voice
// rhasspy/piper-voices publishes is NOT listed here as static data - it's fetched and parsed at
// runtime from upstream's own `voices.json` (see [PiperVoicesIndex]) so the browsable Piper list
// is always current with upstream instead of a hand-maintained snapshot of it.
object BuiltInCatalog {
    // The one legitimately hand-curated Piper voice: a known-good "first download" pick, not a
    // stand-in for the full voice list (that's fetched dynamically - see [PiperVoicesIndex]).
    // repoId is sourced from PiperVoicesIndex.REPO_ID (SSOT: the one place that literal lives),
    // never re-typed here.
    //
    // Every sha256 below was obtained by actually fetching the real upstream file (the exact
    // `repoPath` at `revision`) and hashing its bytes - never invented (rule 4, fail closed). If
    // upstream ever republishes a file's content at the same path, the pinned hash will correctly
    // start rejecting it until this catalog is updated to match, which is the intended behavior.
    val PIPER_LESSAC =
        BuiltInModel(
            id = "piper-en_US-lessac-medium",
            displayName = "Piper - Lessac (English, United States, medium)",
            repoId = PiperVoicesIndex.REPO_ID,
            approxSizeMb = 63,
            files =
                listOf(
                    BuiltInFile(
                        repoPath = "en/en_US/lessac/medium/en_US-lessac-medium.onnx",
                        localName = "en_US-lessac-medium.onnx",
                        sha256 = "5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f",
                    ),
                    BuiltInFile(
                        repoPath = "en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
                        localName = "en_US-lessac-medium.onnx.json",
                        sha256 = "efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0",
                    ),
                ),
            note = "Fast, clear English voice. Recommended first download.",
        )

    val KITTEN_NANO =
        BuiltInModel(
            id = "kittentts-nano-0.1",
            displayName = "KittenTTS - Nano (English)",
            repoId = "KittenML/kitten-tts-nano-0.1",
            approxSizeMb = 24,
            files =
                listOf(
                    BuiltInFile(
                        "kitten_tts_nano_v0_1.onnx",
                        "kitten_tts_nano_v0_1.onnx",
                        sha256 = "8818e8f06eb34bc9d17ba3987a9b513c5759e2f5f119b1242cfbb503d12bdc0a",
                    ),
                    BuiltInFile(
                        "config.json",
                        "config.json",
                        sha256 = "987312b7ca0b25dca88229a2002c891220d6cd5dcb8350107820c65dc4a62bb4",
                    ),
                    BuiltInFile(
                        "voices.npz",
                        "voices.npz",
                        sha256 = "d1f3b3ce46c5fa79fa0019824bf572fea74f6a84c88b079cfecd700118d00b62",
                    ),
                ),
            note = "Tiny 8-voice English model. Smallest download.",
        )

    // The fp32 export (the q8f16 variant segfaults ONNX Runtime - docs/MODEL-VERIFICATION.md).
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
                    BuiltInFile(
                        "onnx/model.onnx",
                        "model.onnx",
                        sha256 = "8fbea51ea711f2af382e88c833d9e288c6dc82ce5e98421ea61c058ce21a34cb",
                    ),
                    BuiltInFile(
                        "config.json",
                        "config.json",
                        sha256 = "df34b4f930b23447cd4dc410fabfb42eb3f24e803e6c3f97d618fb359380a36f",
                    ),
                    BuiltInFile(
                        "tokenizer.json",
                        "tokenizer.json",
                        sha256 = "77a02c8e164413299b4b4c403b14f8e0e1c1b727db4d46a09d6327b861060a34",
                    ),
                    BuiltInFile(
                        "voices/af_heart.bin",
                        "voices/af_heart.bin",
                        sha256 = "d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b",
                    ),
                    BuiltInFile(
                        "voices/af_bella.bin",
                        "voices/af_bella.bin",
                        sha256 = "f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b",
                    ),
                    BuiltInFile(
                        "voices/am_michael.bin",
                        "voices/am_michael.bin",
                        sha256 = "1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1",
                    ),
                    BuiltInFile(
                        "voices/bf_emma.bin",
                        "voices/bf_emma.bin",
                        sha256 = "669fe0647f9dd04fcab92f1439a40eeb4c8b4ab1f82e4996fe3d918ce4a63b73",
                    ),
                    BuiltInFile(
                        "voices/bm_george.bin",
                        "voices/bm_george.bin",
                        sha256 = "c4b235a4c1f2cd3b939fed08b899ce9385638b763f7b73a59616c4fc9bd6c9bc",
                    ),
                ),
            note = "Highest quality, 5 English voices. Larger download (fp32).",
        )

    // MiaoMint/MeloTTS-ONNX's sherpa-onnx English export (onnx_exports/en_v2) - PROVEN to produce
    // real, non-silent speech by scripts/model-verify/run_melo2.py (10.54s, 908KB, peak 0.293).
    // Ships its own symbol table (tokens.txt) and G2P dictionary (lexicon.txt), which is what lets
    // MeloEngine avoid a hardcoded phoneme map (SSOT). Replaces the abandoned seasonstudio export,
    // which ran shape-correctly but produced silence (docs/MODEL-VERIFICATION.md).
    val MELO_EN =
        BuiltInModel(
            id = "melotts-en-v2",
            displayName = "MeloTTS - English v2",
            repoId = "MiaoMint/MeloTTS-ONNX",
            approxSizeMb = 175,
            files =
                listOf(
                    BuiltInFile(
                        repoPath = "onnx_exports/en_v2/model.onnx",
                        localName = "model.onnx",
                        sha256 = "fce9200aa0c03d98bc847d15934658f74aefc75730c7246f4b2a6c4de0243f40",
                    ),
                    BuiltInFile(
                        repoPath = "onnx_exports/en_v2/tokens.txt",
                        localName = "tokens.txt",
                        sha256 = "96026b19956133d23b7048c0e75466d061f31dea4e848315689259876b531376",
                    ),
                    BuiltInFile(
                        repoPath = "onnx_exports/en_v2/lexicon.txt",
                        localName = "lexicon.txt",
                        sha256 = "8eae1a826d28bfb1fde2aa0202a43eb244d475d8418cf3ee7724cdf561a63a88",
                    ),
                    BuiltInFile(
                        repoPath = "onnx_exports/en_v2/metadata.json",
                        localName = "metadata.json",
                        sha256 = "9edd7e3710ff617bf7cfd07b6d8414f8eed07f724b7524ed5bca3fdb1db37596",
                    ),
                ),
            note = "Multi-speaker English VITS model.",
        )

    // CosyVoice3-0.5B, the Tier-C autoregressive model - the deployable GGUF sibling of the
    // CosyVoice2 model proven in PyTorch (docs/MODEL-VERIFICATION.md). It runs ONLY on the native
    // ggml backend built with -PwithCosyVoice, so it declares requiresRuntimeId = the CosyVoice
    // runtime id: in a standard APK that runtime is unavailable and the recommended list hides this
    // entry (no broken one-tap); in a native build it appears as the 5th model. The minimal 745 MB
    // combo (Q4_K LLM + Q8_0 flow + F16 HiFT + voices) is the four-GGUF set CosyVoice2Engine
    // fingerprints by name - the repo already ships them under those exact stage-prefixed names.
    val COSYVOICE3_05B =
        BuiltInModel(
            id = "cosyvoice3-0.5b",
            displayName = "CosyVoice3-0.5B (multilingual, native)",
            repoId = "cstr/cosyvoice3-0.5b-2512-GGUF",
            approxSizeMb = 745,
            files =
                listOf(
                    BuiltInFile(
                        "cosyvoice3-llm-q4_k.gguf",
                        "cosyvoice3-llm-q4_k.gguf",
                        sha256 = "33d899490108bce896c3448bca40ebd994daa125ce0b118e6267f6b84fc09530",
                    ),
                    BuiltInFile(
                        "cosyvoice3-flow-q8_0.gguf",
                        "cosyvoice3-flow-q8_0.gguf",
                        sha256 = "ab264405ca243baf9aa2f2f26959d213d3e3ca9fb204db2a0b582ed2ca243d86",
                    ),
                    BuiltInFile(
                        "cosyvoice3-hift-f16.gguf",
                        "cosyvoice3-hift-f16.gguf",
                        sha256 = "6df249e52901714f30f9bb163ff47cedfa56f6df3385567b657610eeb8b8cfd3",
                    ),
                    BuiltInFile(
                        "cosyvoice3-voices.gguf",
                        "cosyvoice3-voices.gguf",
                        sha256 = "73b5cac894d8e715d418d0228d2878ad6e836a809ccb85799ff08ae5f63ac6a2",
                    ),
                ),
            note = "Highest quality, multilingual. Needs the native (-PwithCosyVoice) build; large download.",
            requiresRuntimeId = "cosyvoice",
        )

    /** Every recommended model, in display order (smallest-first is friendlier, but quality-first here). */
    val ALL: List<BuiltInModel> = listOf(PIPER_LESSAC, KITTEN_NANO, KOKORO_82M, MELO_EN, COSYVOICE3_05B)
}
