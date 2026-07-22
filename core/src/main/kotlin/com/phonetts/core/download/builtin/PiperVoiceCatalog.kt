package com.phonetts.core.download.builtin

// Generated from rhasspy/piper-voices' published `voices.json` index (the piper1-gpl voice
// family's own manifest of every voice it ships) so the Piper catalog stays complete without
// hand-picking voices one at a time (CLAUDE.md rule 1 — SSOT: this is data, not per-voice code).
// Each entry names exactly the two files PiperEngine.inspect() needs (<voice>.onnx +
// <voice>.onnx.json) at their real repo path, so every voice downloads on its own — never the
// whole repo — and resolves through the existing Piper inspect()/resolve() path unchanged; no
// engine code names any of these voices.
//
// To refresh after upstream adds voices: download
// https://huggingface.co/rhasspy/piper-voices/resolve/main/voices.json and regenerate this file's
// `ALL` list from it (one BuiltInModel per voice key, taking the key's `.onnx`/`.onnx.json` repo
// paths verbatim) — a mechanical transform, not hand-picking.
// Snapshot taken 2026-07-22 (main revision): 166 voices across every language
// rhasspy/piper-voices publishes.
object PiperVoiceCatalog {
    /** Every voice rhasspy/piper-voices publishes, one [BuiltInModel] per `<voice>.onnx`. */
    val ALL: List<BuiltInModel> =
        listOf(
            BuiltInModel(
                id = "piper-ar_JO-kareem-low",
                displayName = "Piper — Kareem (Arabic, Jordan, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ar/ar_JO/kareem/low/ar_JO-kareem-low.onnx",
                            localName = "ar_JO-kareem-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ar/ar_JO/kareem/low/ar_JO-kareem-low.onnx.json",
                            localName = "ar_JO-kareem-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ar_JO-kareem-medium",
                displayName = "Piper — Kareem (Arabic, Jordan, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ar/ar_JO/kareem/medium/ar_JO-kareem-medium.onnx",
                            localName = "ar_JO-kareem-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ar/ar_JO/kareem/medium/ar_JO-kareem-medium.onnx.json",
                            localName = "ar_JO-kareem-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-bg_BG-dimitar-medium",
                displayName = "Piper — Dimitar (Bulgarian, Bulgaria, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "bg/bg_BG/dimitar/medium/bg_BG-dimitar-medium.onnx",
                            localName = "bg_BG-dimitar-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "bg/bg_BG/dimitar/medium/bg_BG-dimitar-medium.onnx.json",
                            localName = "bg_BG-dimitar-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-bn_BD-google-medium",
                displayName = "Piper — Google (Bengali, Bangladesh, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "bn/bn_BD/google/medium/bn_BD-google-medium.onnx",
                            localName = "bn_BD-google-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "bn/bn_BD/google/medium/bn_BD-google-medium.onnx.json",
                            localName = "bn_BD-google-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ca_ES-upc_ona-medium",
                displayName = "Piper — Upc Ona (Catalan, Spain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ca/ca_ES/upc_ona/medium/ca_ES-upc_ona-medium.onnx",
                            localName = "ca_ES-upc_ona-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ca/ca_ES/upc_ona/medium/ca_ES-upc_ona-medium.onnx.json",
                            localName = "ca_ES-upc_ona-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ca_ES-upc_ona-x_low",
                displayName = "Piper — Upc Ona (Catalan, Spain, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 21,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ca/ca_ES/upc_ona/x_low/ca_ES-upc_ona-x_low.onnx",
                            localName = "ca_ES-upc_ona-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ca/ca_ES/upc_ona/x_low/ca_ES-upc_ona-x_low.onnx.json",
                            localName = "ca_ES-upc_ona-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ca_ES-upc_pau-x_low",
                displayName = "Piper — Upc Pau (Catalan, Spain, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 28,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ca/ca_ES/upc_pau/x_low/ca_ES-upc_pau-x_low.onnx",
                            localName = "ca_ES-upc_pau-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ca/ca_ES/upc_pau/x_low/ca_ES-upc_pau-x_low.onnx.json",
                            localName = "ca_ES-upc_pau-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-cs_CZ-jirka-low",
                displayName = "Piper — Jirka (Czech, Czech Republic, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "cs/cs_CZ/jirka/low/cs_CZ-jirka-low.onnx",
                            localName = "cs_CZ-jirka-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "cs/cs_CZ/jirka/low/cs_CZ-jirka-low.onnx.json",
                            localName = "cs_CZ-jirka-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-cs_CZ-jirka-medium",
                displayName = "Piper — Jirka (Czech, Czech Republic, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "cs/cs_CZ/jirka/medium/cs_CZ-jirka-medium.onnx",
                            localName = "cs_CZ-jirka-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "cs/cs_CZ/jirka/medium/cs_CZ-jirka-medium.onnx.json",
                            localName = "cs_CZ-jirka-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-cy_GB-bu_tts-medium",
                displayName = "Piper — Bu Tts (Welsh, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "cy/cy_GB/bu_tts/medium/cy_GB-bu_tts-medium.onnx",
                            localName = "cy_GB-bu_tts-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "cy/cy_GB/bu_tts/medium/cy_GB-bu_tts-medium.onnx.json",
                            localName = "cy_GB-bu_tts-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-cy_GB-gwryw_gogleddol-medium",
                displayName = "Piper — Gwryw Gogleddol (Welsh, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "cy/cy_GB/gwryw_gogleddol/medium/cy_GB-gwryw_gogleddol-medium.onnx",
                            localName = "cy_GB-gwryw_gogleddol-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "cy/cy_GB/gwryw_gogleddol/medium/cy_GB-gwryw_gogleddol-medium.onnx.json",
                            localName = "cy_GB-gwryw_gogleddol-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-da_DK-talesyntese-medium",
                displayName = "Piper — Talesyntese (Danish, Denmark, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "da/da_DK/talesyntese/medium/da_DK-talesyntese-medium.onnx",
                            localName = "da_DK-talesyntese-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "da/da_DK/talesyntese/medium/da_DK-talesyntese-medium.onnx.json",
                            localName = "da_DK-talesyntese-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-eva_k-x_low",
                displayName = "Piper — Eva K (German, Germany, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 21,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/eva_k/x_low/de_DE-eva_k-x_low.onnx",
                            localName = "de_DE-eva_k-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/eva_k/x_low/de_DE-eva_k-x_low.onnx.json",
                            localName = "de_DE-eva_k-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-karlsson-low",
                displayName = "Piper — Karlsson (German, Germany, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/karlsson/low/de_DE-karlsson-low.onnx",
                            localName = "de_DE-karlsson-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/karlsson/low/de_DE-karlsson-low.onnx.json",
                            localName = "de_DE-karlsson-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-kerstin-low",
                displayName = "Piper — Kerstin (German, Germany, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/kerstin/low/de_DE-kerstin-low.onnx",
                            localName = "de_DE-kerstin-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/kerstin/low/de_DE-kerstin-low.onnx.json",
                            localName = "de_DE-kerstin-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-mls-medium",
                displayName = "Piper — Mls (German, Germany, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/mls/medium/de_DE-mls-medium.onnx",
                            localName = "de_DE-mls-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/mls/medium/de_DE-mls-medium.onnx.json",
                            localName = "de_DE-mls-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-pavoque-low",
                displayName = "Piper — Pavoque (German, Germany, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/pavoque/low/de_DE-pavoque-low.onnx",
                            localName = "de_DE-pavoque-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/pavoque/low/de_DE-pavoque-low.onnx.json",
                            localName = "de_DE-pavoque-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-ramona-low",
                displayName = "Piper — Ramona (German, Germany, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/ramona/low/de_DE-ramona-low.onnx",
                            localName = "de_DE-ramona-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/ramona/low/de_DE-ramona-low.onnx.json",
                            localName = "de_DE-ramona-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-thorsten-high",
                displayName = "Piper — Thorsten (German, Germany, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/thorsten/high/de_DE-thorsten-high.onnx",
                            localName = "de_DE-thorsten-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/thorsten/high/de_DE-thorsten-high.onnx.json",
                            localName = "de_DE-thorsten-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-thorsten-low",
                displayName = "Piper — Thorsten (German, Germany, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/thorsten/low/de_DE-thorsten-low.onnx",
                            localName = "de_DE-thorsten-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/thorsten/low/de_DE-thorsten-low.onnx.json",
                            localName = "de_DE-thorsten-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-thorsten-medium",
                displayName = "Piper — Thorsten (German, Germany, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx",
                            localName = "de_DE-thorsten-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx.json",
                            localName = "de_DE-thorsten-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-de_DE-thorsten_emotional-medium",
                displayName = "Piper — Thorsten Emotional (German, Germany, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "de/de_DE/thorsten_emotional/medium/de_DE-thorsten_emotional-medium.onnx",
                            localName = "de_DE-thorsten_emotional-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "de/de_DE/thorsten_emotional/medium/de_DE-thorsten_emotional-medium.onnx.json",
                            localName = "de_DE-thorsten_emotional-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-el_GR-joy-medium",
                displayName = "Piper — Joy (Greek, Greece, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "el/el_GR/joy/medium/el_GR-joy-medium.onnx",
                            localName = "el_GR-joy-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "el/el_GR/joy/medium/el_GR-joy-medium.onnx.json",
                            localName = "el_GR-joy-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-el_GR-rapunzelina-low",
                displayName = "Piper — Rapunzelina (Greek, Greece, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "el/el_GR/rapunzelina/low/el_GR-rapunzelina-low.onnx",
                            localName = "el_GR-rapunzelina-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "el/el_GR/rapunzelina/low/el_GR-rapunzelina-low.onnx.json",
                            localName = "el_GR-rapunzelina-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-el_GR-rapunzelina-medium",
                displayName = "Piper — Rapunzelina (Greek, Greece, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "el/el_GR/rapunzelina/medium/el_GR-rapunzelina-medium.onnx",
                            localName = "el_GR-rapunzelina-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "el/el_GR/rapunzelina/medium/el_GR-rapunzelina-medium.onnx.json",
                            localName = "el_GR-rapunzelina-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-alan-low",
                displayName = "Piper — Alan (English, Great Britain, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/alan/low/en_GB-alan-low.onnx",
                            localName = "en_GB-alan-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/alan/low/en_GB-alan-low.onnx.json",
                            localName = "en_GB-alan-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-alan-medium",
                displayName = "Piper — Alan (English, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/alan/medium/en_GB-alan-medium.onnx",
                            localName = "en_GB-alan-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/alan/medium/en_GB-alan-medium.onnx.json",
                            localName = "en_GB-alan-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-alba-medium",
                displayName = "Piper — Alba (English, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/alba/medium/en_GB-alba-medium.onnx",
                            localName = "en_GB-alba-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/alba/medium/en_GB-alba-medium.onnx.json",
                            localName = "en_GB-alba-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-aru-medium",
                displayName = "Piper — Aru (English, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/aru/medium/en_GB-aru-medium.onnx",
                            localName = "en_GB-aru-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/aru/medium/en_GB-aru-medium.onnx.json",
                            localName = "en_GB-aru-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-cori-high",
                displayName = "Piper — Cori (English, Great Britain, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/cori/high/en_GB-cori-high.onnx",
                            localName = "en_GB-cori-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/cori/high/en_GB-cori-high.onnx.json",
                            localName = "en_GB-cori-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-cori-medium",
                displayName = "Piper — Cori (English, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/cori/medium/en_GB-cori-medium.onnx",
                            localName = "en_GB-cori-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/cori/medium/en_GB-cori-medium.onnx.json",
                            localName = "en_GB-cori-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-jenny_dioco-medium",
                displayName = "Piper — Jenny Dioco (English, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/jenny_dioco/medium/en_GB-jenny_dioco-medium.onnx",
                            localName = "en_GB-jenny_dioco-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/jenny_dioco/medium/en_GB-jenny_dioco-medium.onnx.json",
                            localName = "en_GB-jenny_dioco-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-northern_english_male-medium",
                displayName = "Piper — Northern English Male (English, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/northern_english_male/medium/en_GB-northern_english_male-medium.onnx",
                            localName = "en_GB-northern_english_male-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath =
                                "en/en_GB/northern_english_male/medium/en_GB-northern_english_male-medium.onnx.json",
                            localName = "en_GB-northern_english_male-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-semaine-medium",
                displayName = "Piper — Semaine (English, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/semaine/medium/en_GB-semaine-medium.onnx",
                            localName = "en_GB-semaine-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/semaine/medium/en_GB-semaine-medium.onnx.json",
                            localName = "en_GB-semaine-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-southern_english_female-low",
                displayName = "Piper — Southern English Female (English, Great Britain, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/southern_english_female/low/en_GB-southern_english_female-low.onnx",
                            localName = "en_GB-southern_english_female-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath =
                                "en/en_GB/southern_english_female/low/en_GB-southern_english_female-low.onnx.json",
                            localName = "en_GB-southern_english_female-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_GB-vctk-medium",
                displayName = "Piper — Vctk (English, Great Britain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_GB/vctk/medium/en_GB-vctk-medium.onnx",
                            localName = "en_GB-vctk-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_GB/vctk/medium/en_GB-vctk-medium.onnx.json",
                            localName = "en_GB-vctk-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-amy-low",
                displayName = "Piper — Amy (English, United States, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/amy/low/en_US-amy-low.onnx",
                            localName = "en_US-amy-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/amy/low/en_US-amy-low.onnx.json",
                            localName = "en_US-amy-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-amy-medium",
                displayName = "Piper — Amy (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/amy/medium/en_US-amy-medium.onnx",
                            localName = "en_US-amy-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/amy/medium/en_US-amy-medium.onnx.json",
                            localName = "en_US-amy-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-arctic-medium",
                displayName = "Piper — Arctic (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/arctic/medium/en_US-arctic-medium.onnx",
                            localName = "en_US-arctic-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/arctic/medium/en_US-arctic-medium.onnx.json",
                            localName = "en_US-arctic-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-bryce-medium",
                displayName = "Piper — Bryce (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/bryce/medium/en_US-bryce-medium.onnx",
                            localName = "en_US-bryce-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/bryce/medium/en_US-bryce-medium.onnx.json",
                            localName = "en_US-bryce-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-danny-low",
                displayName = "Piper — Danny (English, United States, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/danny/low/en_US-danny-low.onnx",
                            localName = "en_US-danny-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/danny/low/en_US-danny-low.onnx.json",
                            localName = "en_US-danny-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-hfc_female-medium",
                displayName = "Piper — Hfc Female (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/hfc_female/medium/en_US-hfc_female-medium.onnx",
                            localName = "en_US-hfc_female-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/hfc_female/medium/en_US-hfc_female-medium.onnx.json",
                            localName = "en_US-hfc_female-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-hfc_male-medium",
                displayName = "Piper — Hfc Male (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/hfc_male/medium/en_US-hfc_male-medium.onnx",
                            localName = "en_US-hfc_male-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/hfc_male/medium/en_US-hfc_male-medium.onnx.json",
                            localName = "en_US-hfc_male-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-joe-medium",
                displayName = "Piper — Joe (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/joe/medium/en_US-joe-medium.onnx",
                            localName = "en_US-joe-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/joe/medium/en_US-joe-medium.onnx.json",
                            localName = "en_US-joe-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-john-medium",
                displayName = "Piper — John (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/john/medium/en_US-john-medium.onnx",
                            localName = "en_US-john-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/john/medium/en_US-john-medium.onnx.json",
                            localName = "en_US-john-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-kathleen-low",
                displayName = "Piper — Kathleen (English, United States, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/kathleen/low/en_US-kathleen-low.onnx",
                            localName = "en_US-kathleen-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/kathleen/low/en_US-kathleen-low.onnx.json",
                            localName = "en_US-kathleen-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-kristin-medium",
                displayName = "Piper — Kristin (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/kristin/medium/en_US-kristin-medium.onnx",
                            localName = "en_US-kristin-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/kristin/medium/en_US-kristin-medium.onnx.json",
                            localName = "en_US-kristin-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-kusal-medium",
                displayName = "Piper — Kusal (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/kusal/medium/en_US-kusal-medium.onnx",
                            localName = "en_US-kusal-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/kusal/medium/en_US-kusal-medium.onnx.json",
                            localName = "en_US-kusal-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-l2arctic-medium",
                displayName = "Piper — L2arctic (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/l2arctic/medium/en_US-l2arctic-medium.onnx",
                            localName = "en_US-l2arctic-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/l2arctic/medium/en_US-l2arctic-medium.onnx.json",
                            localName = "en_US-l2arctic-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-lessac-high",
                displayName = "Piper — Lessac (English, United States, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/lessac/high/en_US-lessac-high.onnx",
                            localName = "en_US-lessac-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/lessac/high/en_US-lessac-high.onnx.json",
                            localName = "en_US-lessac-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-lessac-low",
                displayName = "Piper — Lessac (English, United States, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/lessac/low/en_US-lessac-low.onnx",
                            localName = "en_US-lessac-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/lessac/low/en_US-lessac-low.onnx.json",
                            localName = "en_US-lessac-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-lessac-medium",
                displayName = "Piper — Lessac (English, United States, medium)",
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
            ),
            BuiltInModel(
                id = "piper-en_US-libritts-high",
                displayName = "Piper — Libritts (English, United States, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 137,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/libritts/high/en_US-libritts-high.onnx",
                            localName = "en_US-libritts-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/libritts/high/en_US-libritts-high.onnx.json",
                            localName = "en_US-libritts-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-libritts_r-medium",
                displayName = "Piper — Libritts R (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 79,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/libritts_r/medium/en_US-libritts_r-medium.onnx",
                            localName = "en_US-libritts_r-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/libritts_r/medium/en_US-libritts_r-medium.onnx.json",
                            localName = "en_US-libritts_r-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-ljspeech-high",
                displayName = "Piper — Ljspeech (English, United States, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/ljspeech/high/en_US-ljspeech-high.onnx",
                            localName = "en_US-ljspeech-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/ljspeech/high/en_US-ljspeech-high.onnx.json",
                            localName = "en_US-ljspeech-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-ljspeech-medium",
                displayName = "Piper — Ljspeech (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/ljspeech/medium/en_US-ljspeech-medium.onnx",
                            localName = "en_US-ljspeech-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/ljspeech/medium/en_US-ljspeech-medium.onnx.json",
                            localName = "en_US-ljspeech-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-mike-medium",
                displayName = "Piper — Mike (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/mike/medium/en_US-mike-medium.onnx",
                            localName = "en_US-mike-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/mike/medium/en_US-mike-medium.onnx.json",
                            localName = "en_US-mike-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-norman-medium",
                displayName = "Piper — Norman (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/norman/medium/en_US-norman-medium.onnx",
                            localName = "en_US-norman-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/norman/medium/en_US-norman-medium.onnx.json",
                            localName = "en_US-norman-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-reza_ibrahim-medium",
                displayName = "Piper — Reza Ibrahim (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/reza_ibrahim/medium/en_US-reza_ibrahim-medium.onnx",
                            localName = "en_US-reza_ibrahim-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/reza_ibrahim/medium/en_US-reza_ibrahim-medium.onnx.json",
                            localName = "en_US-reza_ibrahim-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-ryan-high",
                displayName = "Piper — Ryan (English, United States, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 121,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/ryan/high/en_US-ryan-high.onnx",
                            localName = "en_US-ryan-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/ryan/high/en_US-ryan-high.onnx.json",
                            localName = "en_US-ryan-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-ryan-low",
                displayName = "Piper — Ryan (English, United States, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/ryan/low/en_US-ryan-low.onnx",
                            localName = "en_US-ryan-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/ryan/low/en_US-ryan-low.onnx.json",
                            localName = "en_US-ryan-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-ryan-medium",
                displayName = "Piper — Ryan (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/ryan/medium/en_US-ryan-medium.onnx",
                            localName = "en_US-ryan-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/ryan/medium/en_US-ryan-medium.onnx.json",
                            localName = "en_US-ryan-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-en_US-sam-medium",
                displayName = "Piper — Sam (English, United States, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "en/en_US/sam/medium/en_US-sam-medium.onnx",
                            localName = "en_US-sam-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "en/en_US/sam/medium/en_US-sam-medium.onnx.json",
                            localName = "en_US-sam-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_AR-daniela-high",
                displayName = "Piper — Daniela (Spanish, Argentina, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_AR/daniela/high/es_AR-daniela-high.onnx",
                            localName = "es_AR-daniela-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_AR/daniela/high/es_AR-daniela-high.onnx.json",
                            localName = "es_AR-daniela-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_ES-carlfm-x_low",
                displayName = "Piper — Carlfm (Spanish, Spain, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 28,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_ES/carlfm/x_low/es_ES-carlfm-x_low.onnx",
                            localName = "es_ES-carlfm-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_ES/carlfm/x_low/es_ES-carlfm-x_low.onnx.json",
                            localName = "es_ES-carlfm-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_ES-davefx-medium",
                displayName = "Piper — Davefx (Spanish, Spain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_ES/davefx/medium/es_ES-davefx-medium.onnx",
                            localName = "es_ES-davefx-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_ES/davefx/medium/es_ES-davefx-medium.onnx.json",
                            localName = "es_ES-davefx-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_ES-mls_10246-low",
                displayName = "Piper — Mls 10246 (Spanish, Spain, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_ES/mls_10246/low/es_ES-mls_10246-low.onnx",
                            localName = "es_ES-mls_10246-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_ES/mls_10246/low/es_ES-mls_10246-low.onnx.json",
                            localName = "es_ES-mls_10246-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_ES-mls_9972-low",
                displayName = "Piper — Mls 9972 (Spanish, Spain, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_ES/mls_9972/low/es_ES-mls_9972-low.onnx",
                            localName = "es_ES-mls_9972-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_ES/mls_9972/low/es_ES-mls_9972-low.onnx.json",
                            localName = "es_ES-mls_9972-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_ES-sharvard-medium",
                displayName = "Piper — Sharvard (Spanish, Spain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_ES/sharvard/medium/es_ES-sharvard-medium.onnx",
                            localName = "es_ES-sharvard-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_ES/sharvard/medium/es_ES-sharvard-medium.onnx.json",
                            localName = "es_ES-sharvard-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_MX-ald-medium",
                displayName = "Piper — Ald (Spanish, Mexico, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_MX/ald/medium/es_MX-ald-medium.onnx",
                            localName = "es_MX-ald-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_MX/ald/medium/es_MX-ald-medium.onnx.json",
                            localName = "es_MX-ald-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_MX-ald-x_low",
                displayName = "Piper — Ald (Spanish, Mexico, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 21,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_MX/ald/x_low/es_MX-ald-x_low.onnx",
                            localName = "es_MX-ald-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_MX/ald/x_low/es_MX-ald-x_low.onnx.json",
                            localName = "es_MX-ald-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-es_MX-claude-high",
                displayName = "Piper — Claude (Spanish, Mexico, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "es/es_MX/claude/high/es_MX-claude-high.onnx",
                            localName = "es_MX-claude-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "es/es_MX/claude/high/es_MX-claude-high.onnx.json",
                            localName = "es_MX-claude-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-eu_ES-antton-medium",
                displayName = "Piper — Antton (Basque, Spain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "eu/eu_ES/antton/medium/eu_ES-antton-medium.onnx",
                            localName = "eu_ES-antton-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "eu/eu_ES/antton/medium/eu_ES-antton-medium.onnx.json",
                            localName = "eu_ES-antton-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-eu_ES-maider-medium",
                displayName = "Piper — Maider (Basque, Spain, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "eu/eu_ES/maider/medium/eu_ES-maider-medium.onnx",
                            localName = "eu_ES-maider-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "eu/eu_ES/maider/medium/eu_ES-maider-medium.onnx.json",
                            localName = "eu_ES-maider-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fa_IR-amir-medium",
                displayName = "Piper — Amir (Farsi, Iran, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fa/fa_IR/amir/medium/fa_IR-amir-medium.onnx",
                            localName = "fa_IR-amir-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fa/fa_IR/amir/medium/fa_IR-amir-medium.onnx.json",
                            localName = "fa_IR-amir-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fa_IR-ganji-medium",
                displayName = "Piper — Ganji (Farsi, Iran, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fa/fa_IR/ganji/medium/fa_IR-ganji-medium.onnx",
                            localName = "fa_IR-ganji-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fa/fa_IR/ganji/medium/fa_IR-ganji-medium.onnx.json",
                            localName = "fa_IR-ganji-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fa_IR-ganji_adabi-medium",
                displayName = "Piper — Ganji Adabi (Farsi, Iran, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fa/fa_IR/ganji_adabi/medium/fa_IR-ganji_adabi-medium.onnx",
                            localName = "fa_IR-ganji_adabi-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fa/fa_IR/ganji_adabi/medium/fa_IR-ganji_adabi-medium.onnx.json",
                            localName = "fa_IR-ganji_adabi-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fa_IR-gyro-medium",
                displayName = "Piper — Gyro (Farsi, Iran, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fa/fa_IR/gyro/medium/fa_IR-gyro-medium.onnx",
                            localName = "fa_IR-gyro-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fa/fa_IR/gyro/medium/fa_IR-gyro-medium.onnx.json",
                            localName = "fa_IR-gyro-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fa_IR-reza_ibrahim-medium",
                displayName = "Piper — Reza Ibrahim (Farsi, Iran, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fa/fa_IR/reza_ibrahim/medium/fa_IR-reza_ibrahim-medium.onnx",
                            localName = "fa_IR-reza_ibrahim-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fa/fa_IR/reza_ibrahim/medium/fa_IR-reza_ibrahim-medium.onnx.json",
                            localName = "fa_IR-reza_ibrahim-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fi_FI-harri-low",
                displayName = "Piper — Harri (Finnish, Finland, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 70,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fi/fi_FI/harri/low/fi_FI-harri-low.onnx",
                            localName = "fi_FI-harri-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fi/fi_FI/harri/low/fi_FI-harri-low.onnx.json",
                            localName = "fi_FI-harri-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fi_FI-harri-medium",
                displayName = "Piper — Harri (Finnish, Finland, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fi/fi_FI/harri/medium/fi_FI-harri-medium.onnx",
                            localName = "fi_FI-harri-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fi/fi_FI/harri/medium/fi_FI-harri-medium.onnx.json",
                            localName = "fi_FI-harri-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fr_FR-gilles-low",
                displayName = "Piper — Gilles (French, France, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fr/fr_FR/gilles/low/fr_FR-gilles-low.onnx",
                            localName = "fr_FR-gilles-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fr/fr_FR/gilles/low/fr_FR-gilles-low.onnx.json",
                            localName = "fr_FR-gilles-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fr_FR-mls-medium",
                displayName = "Piper — Mls (French, France, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fr/fr_FR/mls/medium/fr_FR-mls-medium.onnx",
                            localName = "fr_FR-mls-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fr/fr_FR/mls/medium/fr_FR-mls-medium.onnx.json",
                            localName = "fr_FR-mls-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fr_FR-mls_1840-low",
                displayName = "Piper — Mls 1840 (French, France, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fr/fr_FR/mls_1840/low/fr_FR-mls_1840-low.onnx",
                            localName = "fr_FR-mls_1840-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fr/fr_FR/mls_1840/low/fr_FR-mls_1840-low.onnx.json",
                            localName = "fr_FR-mls_1840-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fr_FR-siwis-low",
                displayName = "Piper — Siwis (French, France, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 28,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fr/fr_FR/siwis/low/fr_FR-siwis-low.onnx",
                            localName = "fr_FR-siwis-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fr/fr_FR/siwis/low/fr_FR-siwis-low.onnx.json",
                            localName = "fr_FR-siwis-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fr_FR-siwis-medium",
                displayName = "Piper — Siwis (French, France, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fr/fr_FR/siwis/medium/fr_FR-siwis-medium.onnx",
                            localName = "fr_FR-siwis-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fr/fr_FR/siwis/medium/fr_FR-siwis-medium.onnx.json",
                            localName = "fr_FR-siwis-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fr_FR-tom-medium",
                displayName = "Piper — Tom (French, France, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fr/fr_FR/tom/medium/fr_FR-tom-medium.onnx",
                            localName = "fr_FR-tom-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fr/fr_FR/tom/medium/fr_FR-tom-medium.onnx.json",
                            localName = "fr_FR-tom-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-fr_FR-upmc-medium",
                displayName = "Piper — Upmc (French, France, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "fr/fr_FR/upmc/medium/fr_FR-upmc-medium.onnx",
                            localName = "fr_FR-upmc-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "fr/fr_FR/upmc/medium/fr_FR-upmc-medium.onnx.json",
                            localName = "fr_FR-upmc-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-hi_IN-pratham-medium",
                displayName = "Piper — Pratham (Hindi, India, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx",
                            localName = "hi_IN-pratham-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx.json",
                            localName = "hi_IN-pratham-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-hi_IN-priyamvada-medium",
                displayName = "Piper — Priyamvada (Hindi, India, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx",
                            localName = "hi_IN-priyamvada-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx.json",
                            localName = "hi_IN-priyamvada-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-hi_IN-rohan-medium",
                displayName = "Piper — Rohan (Hindi, India, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "hi/hi_IN/rohan/medium/hi_IN-rohan-medium.onnx",
                            localName = "hi_IN-rohan-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "hi/hi_IN/rohan/medium/hi_IN-rohan-medium.onnx.json",
                            localName = "hi_IN-rohan-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-hu_HU-anna-medium",
                displayName = "Piper — Anna (Hungarian, Hungary, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "hu/hu_HU/anna/medium/hu_HU-anna-medium.onnx",
                            localName = "hu_HU-anna-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "hu/hu_HU/anna/medium/hu_HU-anna-medium.onnx.json",
                            localName = "hu_HU-anna-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-hu_HU-berta-medium",
                displayName = "Piper — Berta (Hungarian, Hungary, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "hu/hu_HU/berta/medium/hu_HU-berta-medium.onnx",
                            localName = "hu_HU-berta-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "hu/hu_HU/berta/medium/hu_HU-berta-medium.onnx.json",
                            localName = "hu_HU-berta-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-hu_HU-imre-medium",
                displayName = "Piper — Imre (Hungarian, Hungary, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "hu/hu_HU/imre/medium/hu_HU-imre-medium.onnx",
                            localName = "hu_HU-imre-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "hu/hu_HU/imre/medium/hu_HU-imre-medium.onnx.json",
                            localName = "hu_HU-imre-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-id_ID-news_tts-medium",
                displayName = "Piper — News Tts (Indonesian, Indonesia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "id/id_ID/news_tts/medium/id_ID-news_tts-medium.onnx",
                            localName = "id_ID-news_tts-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "id/id_ID/news_tts/medium/id_ID-news_tts-medium.onnx.json",
                            localName = "id_ID-news_tts-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-is_IS-bui-medium",
                displayName = "Piper — Bui (Icelandic, Iceland, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 76,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "is/is_IS/bui/medium/is_IS-bui-medium.onnx",
                            localName = "is_IS-bui-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "is/is_IS/bui/medium/is_IS-bui-medium.onnx.json",
                            localName = "is_IS-bui-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-is_IS-salka-medium",
                displayName = "Piper — Salka (Icelandic, Iceland, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 76,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "is/is_IS/salka/medium/is_IS-salka-medium.onnx",
                            localName = "is_IS-salka-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "is/is_IS/salka/medium/is_IS-salka-medium.onnx.json",
                            localName = "is_IS-salka-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-is_IS-steinn-medium",
                displayName = "Piper — Steinn (Icelandic, Iceland, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 76,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "is/is_IS/steinn/medium/is_IS-steinn-medium.onnx",
                            localName = "is_IS-steinn-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "is/is_IS/steinn/medium/is_IS-steinn-medium.onnx.json",
                            localName = "is_IS-steinn-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-is_IS-ugla-medium",
                displayName = "Piper — Ugla (Icelandic, Iceland, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 76,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "is/is_IS/ugla/medium/is_IS-ugla-medium.onnx",
                            localName = "is_IS-ugla-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "is/is_IS/ugla/medium/is_IS-ugla-medium.onnx.json",
                            localName = "is_IS-ugla-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-it_IT-paola-medium",
                displayName = "Piper — Paola (Italian, Italy, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "it/it_IT/paola/medium/it_IT-paola-medium.onnx",
                            localName = "it_IT-paola-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "it/it_IT/paola/medium/it_IT-paola-medium.onnx.json",
                            localName = "it_IT-paola-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-it_IT-riccardo-x_low",
                displayName = "Piper — Riccardo (Italian, Italy, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 28,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "it/it_IT/riccardo/x_low/it_IT-riccardo-x_low.onnx",
                            localName = "it_IT-riccardo-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "it/it_IT/riccardo/x_low/it_IT-riccardo-x_low.onnx.json",
                            localName = "it_IT-riccardo-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ka_GE-natia-medium",
                displayName = "Piper — Natia (Georgian, Georgia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ka/ka_GE/natia/medium/ka_GE-natia-medium.onnx",
                            localName = "ka_GE-natia-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ka/ka_GE/natia/medium/ka_GE-natia-medium.onnx.json",
                            localName = "ka_GE-natia-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-kk_KZ-iseke-x_low",
                displayName = "Piper — Iseke (Kazakh, Kazakhstan, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 28,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "kk/kk_KZ/iseke/x_low/kk_KZ-iseke-x_low.onnx",
                            localName = "kk_KZ-iseke-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "kk/kk_KZ/iseke/x_low/kk_KZ-iseke-x_low.onnx.json",
                            localName = "kk_KZ-iseke-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-kk_KZ-issai-high",
                displayName = "Piper — Issai (Kazakh, Kazakhstan, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 128,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "kk/kk_KZ/issai/high/kk_KZ-issai-high.onnx",
                            localName = "kk_KZ-issai-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "kk/kk_KZ/issai/high/kk_KZ-issai-high.onnx.json",
                            localName = "kk_KZ-issai-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-kk_KZ-raya-x_low",
                displayName = "Piper — Raya (Kazakh, Kazakhstan, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 28,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "kk/kk_KZ/raya/x_low/kk_KZ-raya-x_low.onnx",
                            localName = "kk_KZ-raya-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "kk/kk_KZ/raya/x_low/kk_KZ-raya-x_low.onnx.json",
                            localName = "kk_KZ-raya-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ku_TR-berfin_renas-medium",
                displayName = "Piper — Berfin Renas (Kurmanji Kurdish, Turkey, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ku/ku_TR/berfin_renas/medium/ku_TR-berfin_renas-medium.onnx",
                            localName = "ku_TR-berfin_renas-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ku/ku_TR/berfin_renas/medium/ku_TR-berfin_renas-medium.onnx.json",
                            localName = "ku_TR-berfin_renas-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-lb_LU-marylux-medium",
                displayName = "Piper — Marylux (Luxembourgish, Luxembourg, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "lb/lb_LU/marylux/medium/lb_LU-marylux-medium.onnx",
                            localName = "lb_LU-marylux-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "lb/lb_LU/marylux/medium/lb_LU-marylux-medium.onnx.json",
                            localName = "lb_LU-marylux-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-lv_LV-aivars-medium",
                displayName = "Piper — Aivars (Latvian, Latvia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "lv/lv_LV/aivars/medium/lv_LV-aivars-medium.onnx",
                            localName = "lv_LV-aivars-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "lv/lv_LV/aivars/medium/lv_LV-aivars-medium.onnx.json",
                            localName = "lv_LV-aivars-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ml_IN-arjun-medium",
                displayName = "Piper — Arjun (Malayalam, India, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ml/ml_IN/arjun/medium/ml_IN-arjun-medium.onnx",
                            localName = "ml_IN-arjun-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ml/ml_IN/arjun/medium/ml_IN-arjun-medium.onnx.json",
                            localName = "ml_IN-arjun-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ml_IN-meera-medium",
                displayName = "Piper — Meera (Malayalam, India, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ml/ml_IN/meera/medium/ml_IN-meera-medium.onnx",
                            localName = "ml_IN-meera-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ml/ml_IN/meera/medium/ml_IN-meera-medium.onnx.json",
                            localName = "ml_IN-meera-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ne_NP-chitwan-medium",
                displayName = "Piper — Chitwan (Nepali, Nepal, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ne/ne_NP/chitwan/medium/ne_NP-chitwan-medium.onnx",
                            localName = "ne_NP-chitwan-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ne/ne_NP/chitwan/medium/ne_NP-chitwan-medium.onnx.json",
                            localName = "ne_NP-chitwan-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ne_NP-google-medium",
                displayName = "Piper — Google (Nepali, Nepal, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ne/ne_NP/google/medium/ne_NP-google-medium.onnx",
                            localName = "ne_NP-google-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ne/ne_NP/google/medium/ne_NP-google-medium.onnx.json",
                            localName = "ne_NP-google-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ne_NP-google-x_low",
                displayName = "Piper — Google (Nepali, Nepal, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 28,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ne/ne_NP/google/x_low/ne_NP-google-x_low.onnx",
                            localName = "ne_NP-google-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ne/ne_NP/google/x_low/ne_NP-google-x_low.onnx.json",
                            localName = "ne_NP-google-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_BE-nathalie-medium",
                displayName = "Piper — Nathalie (Dutch, Belgium, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_BE/nathalie/medium/nl_BE-nathalie-medium.onnx",
                            localName = "nl_BE-nathalie-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_BE/nathalie/medium/nl_BE-nathalie-medium.onnx.json",
                            localName = "nl_BE-nathalie-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_BE-nathalie-x_low",
                displayName = "Piper — Nathalie (Dutch, Belgium, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 21,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_BE/nathalie/x_low/nl_BE-nathalie-x_low.onnx",
                            localName = "nl_BE-nathalie-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_BE/nathalie/x_low/nl_BE-nathalie-x_low.onnx.json",
                            localName = "nl_BE-nathalie-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_BE-rdh-medium",
                displayName = "Piper — Rdh (Dutch, Belgium, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_BE/rdh/medium/nl_BE-rdh-medium.onnx",
                            localName = "nl_BE-rdh-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_BE/rdh/medium/nl_BE-rdh-medium.onnx.json",
                            localName = "nl_BE-rdh-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_BE-rdh-x_low",
                displayName = "Piper — Rdh (Dutch, Belgium, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 21,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_BE/rdh/x_low/nl_BE-rdh-x_low.onnx",
                            localName = "nl_BE-rdh-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_BE/rdh/x_low/nl_BE-rdh-x_low.onnx.json",
                            localName = "nl_BE-rdh-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_NL-alex-medium",
                displayName = "Piper — Alex (Dutch, Netherlands, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_NL/alex/medium/nl_NL-alex-medium.onnx",
                            localName = "nl_NL-alex-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_NL/alex/medium/nl_NL-alex-medium.onnx.json",
                            localName = "nl_NL-alex-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_NL-mls-medium",
                displayName = "Piper — Mls (Dutch, Netherlands, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_NL/mls/medium/nl_NL-mls-medium.onnx",
                            localName = "nl_NL-mls-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_NL/mls/medium/nl_NL-mls-medium.onnx.json",
                            localName = "nl_NL-mls-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_NL-mls_5809-low",
                displayName = "Piper — Mls 5809 (Dutch, Netherlands, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_NL/mls_5809/low/nl_NL-mls_5809-low.onnx",
                            localName = "nl_NL-mls_5809-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_NL/mls_5809/low/nl_NL-mls_5809-low.onnx.json",
                            localName = "nl_NL-mls_5809-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_NL-mls_7432-low",
                displayName = "Piper — Mls 7432 (Dutch, Netherlands, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_NL/mls_7432/low/nl_NL-mls_7432-low.onnx",
                            localName = "nl_NL-mls_7432-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_NL/mls_7432/low/nl_NL-mls_7432-low.onnx.json",
                            localName = "nl_NL-mls_7432-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_NL-pim-medium",
                displayName = "Piper — Pim (Dutch, Netherlands, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_NL/pim/medium/nl_NL-pim-medium.onnx",
                            localName = "nl_NL-pim-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_NL/pim/medium/nl_NL-pim-medium.onnx.json",
                            localName = "nl_NL-pim-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-nl_NL-ronnie-medium",
                displayName = "Piper — Ronnie (Dutch, Netherlands, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "nl/nl_NL/ronnie/medium/nl_NL-ronnie-medium.onnx",
                            localName = "nl_NL-ronnie-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "nl/nl_NL/ronnie/medium/nl_NL-ronnie-medium.onnx.json",
                            localName = "nl_NL-ronnie-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-no_NO-nvcc-medium",
                displayName = "Piper — Nvcc (Norwegian, Norway, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "no/no_NO/nvcc/medium/no_NO-nvcc-medium.onnx",
                            localName = "no_NO-nvcc-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "no/no_NO/nvcc/medium/no_NO-nvcc-medium.onnx.json",
                            localName = "no_NO-nvcc-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-no_NO-talesyntese-medium",
                displayName = "Piper — Talesyntese (Norwegian, Norway, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "no/no_NO/talesyntese/medium/no_NO-talesyntese-medium.onnx",
                            localName = "no_NO-talesyntese-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "no/no_NO/talesyntese/medium/no_NO-talesyntese-medium.onnx.json",
                            localName = "no_NO-talesyntese-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pl_PL-bass-high",
                displayName = "Piper — Bass (Polish, Poland, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pl/pl_PL/bass/high/pl_PL-bass-high.onnx",
                            localName = "pl_PL-bass-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pl/pl_PL/bass/high/pl_PL-bass-high.onnx.json",
                            localName = "pl_PL-bass-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pl_PL-darkman-medium",
                displayName = "Piper — Darkman (Polish, Poland, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pl/pl_PL/darkman/medium/pl_PL-darkman-medium.onnx",
                            localName = "pl_PL-darkman-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pl/pl_PL/darkman/medium/pl_PL-darkman-medium.onnx.json",
                            localName = "pl_PL-darkman-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pl_PL-gosia-medium",
                displayName = "Piper — Gosia (Polish, Poland, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pl/pl_PL/gosia/medium/pl_PL-gosia-medium.onnx",
                            localName = "pl_PL-gosia-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pl/pl_PL/gosia/medium/pl_PL-gosia-medium.onnx.json",
                            localName = "pl_PL-gosia-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pl_PL-mc_speech-medium",
                displayName = "Piper — Mc Speech (Polish, Poland, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pl/pl_PL/mc_speech/medium/pl_PL-mc_speech-medium.onnx",
                            localName = "pl_PL-mc_speech-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pl/pl_PL/mc_speech/medium/pl_PL-mc_speech-medium.onnx.json",
                            localName = "pl_PL-mc_speech-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pl_PL-mls_6892-low",
                displayName = "Piper — Mls 6892 (Polish, Poland, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pl/pl_PL/mls_6892/low/pl_PL-mls_6892-low.onnx",
                            localName = "pl_PL-mls_6892-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pl/pl_PL/mls_6892/low/pl_PL-mls_6892-low.onnx.json",
                            localName = "pl_PL-mls_6892-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pt_BR-cadu-medium",
                displayName = "Piper — Cadu (Portuguese, Brazil, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx",
                            localName = "pt_BR-cadu-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx.json",
                            localName = "pt_BR-cadu-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pt_BR-edresson-low",
                displayName = "Piper — Edresson (Portuguese, Brazil, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pt/pt_BR/edresson/low/pt_BR-edresson-low.onnx",
                            localName = "pt_BR-edresson-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pt/pt_BR/edresson/low/pt_BR-edresson-low.onnx.json",
                            localName = "pt_BR-edresson-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pt_BR-faber-medium",
                displayName = "Piper — Faber (Portuguese, Brazil, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx",
                            localName = "pt_BR-faber-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx.json",
                            localName = "pt_BR-faber-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pt_BR-jeff-medium",
                displayName = "Piper — Jeff (Portuguese, Brazil, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pt/pt_BR/jeff/medium/pt_BR-jeff-medium.onnx",
                            localName = "pt_BR-jeff-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pt/pt_BR/jeff/medium/pt_BR-jeff-medium.onnx.json",
                            localName = "pt_BR-jeff-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-pt_PT-tugão-medium",
                displayName = "Piper — Tugão (Portuguese, Portugal, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "pt/pt_PT/tugão/medium/pt_PT-tugão-medium.onnx",
                            localName = "pt_PT-tugão-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "pt/pt_PT/tugão/medium/pt_PT-tugão-medium.onnx.json",
                            localName = "pt_PT-tugão-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ro_RO-mihai-medium",
                displayName = "Piper — Mihai (Romanian, Romania, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ro/ro_RO/mihai/medium/ro_RO-mihai-medium.onnx",
                            localName = "ro_RO-mihai-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ro/ro_RO/mihai/medium/ro_RO-mihai-medium.onnx.json",
                            localName = "ro_RO-mihai-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ru_RU-denis-medium",
                displayName = "Piper — Denis (Russian, Russia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ru/ru_RU/denis/medium/ru_RU-denis-medium.onnx",
                            localName = "ru_RU-denis-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ru/ru_RU/denis/medium/ru_RU-denis-medium.onnx.json",
                            localName = "ru_RU-denis-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ru_RU-dmitri-medium",
                displayName = "Piper — Dmitri (Russian, Russia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx",
                            localName = "ru_RU-dmitri-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx.json",
                            localName = "ru_RU-dmitri-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ru_RU-irina-medium",
                displayName = "Piper — Irina (Russian, Russia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx",
                            localName = "ru_RU-irina-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx.json",
                            localName = "ru_RU-irina-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ru_RU-ruslan-medium",
                displayName = "Piper — Ruslan (Russian, Russia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ru/ru_RU/ruslan/medium/ru_RU-ruslan-medium.onnx",
                            localName = "ru_RU-ruslan-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ru/ru_RU/ruslan/medium/ru_RU-ruslan-medium.onnx.json",
                            localName = "ru_RU-ruslan-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-sk_SK-lili-medium",
                displayName = "Piper — Lili (Slovak, Slovakia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "sk/sk_SK/lili/medium/sk_SK-lili-medium.onnx",
                            localName = "sk_SK-lili-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "sk/sk_SK/lili/medium/sk_SK-lili-medium.onnx.json",
                            localName = "sk_SK-lili-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-sl_SI-artur-medium",
                displayName = "Piper — Artur (Slovenian, Slovenia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "sl/sl_SI/artur/medium/sl_SI-artur-medium.onnx",
                            localName = "sl_SI-artur-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "sl/sl_SI/artur/medium/sl_SI-artur-medium.onnx.json",
                            localName = "sl_SI-artur-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-sq_AL-edon-medium",
                displayName = "Piper — Edon (Albanian, Albania, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "sq/sq_AL/edon/medium/sq_AL-edon-medium.onnx",
                            localName = "sq_AL-edon-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "sq/sq_AL/edon/medium/sq_AL-edon-medium.onnx.json",
                            localName = "sq_AL-edon-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-sr_RS-serbski_institut-medium",
                displayName = "Piper — Serbski Institut (Serbian, Serbia, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "sr/sr_RS/serbski_institut/medium/sr_RS-serbski_institut-medium.onnx",
                            localName = "sr_RS-serbski_institut-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "sr/sr_RS/serbski_institut/medium/sr_RS-serbski_institut-medium.onnx.json",
                            localName = "sr_RS-serbski_institut-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-sv_SE-alma-medium",
                displayName = "Piper — Alma (Swedish, Sweden, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "sv/sv_SE/alma/medium/sv_SE-alma-medium.onnx",
                            localName = "sv_SE-alma-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "sv/sv_SE/alma/medium/sv_SE-alma-medium.onnx.json",
                            localName = "sv_SE-alma-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-sv_SE-lisa-medium",
                displayName = "Piper — Lisa (Swedish, Sweden, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "sv/sv_SE/lisa/medium/sv_SE-lisa-medium.onnx",
                            localName = "sv_SE-lisa-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "sv/sv_SE/lisa/medium/sv_SE-lisa-medium.onnx.json",
                            localName = "sv_SE-lisa-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-sv_SE-nst-medium",
                displayName = "Piper — Nst (Swedish, Sweden, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "sv/sv_SE/nst/medium/sv_SE-nst-medium.onnx",
                            localName = "sv_SE-nst-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "sv/sv_SE/nst/medium/sv_SE-nst-medium.onnx.json",
                            localName = "sv_SE-nst-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-sw_CD-lanfrica-medium",
                displayName = "Piper — Lanfrica (Swahili, Democratic Republic of the Congo, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "sw/sw_CD/lanfrica/medium/sw_CD-lanfrica-medium.onnx",
                            localName = "sw_CD-lanfrica-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "sw/sw_CD/lanfrica/medium/sw_CD-lanfrica-medium.onnx.json",
                            localName = "sw_CD-lanfrica-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-te_IN-maya-medium",
                displayName = "Piper — Maya (Telugu, India, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "te/te_IN/maya/medium/te_IN-maya-medium.onnx",
                            localName = "te_IN-maya-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "te/te_IN/maya/medium/te_IN-maya-medium.onnx.json",
                            localName = "te_IN-maya-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-te_IN-padmavathi-medium",
                displayName = "Piper — Padmavathi (Telugu, India, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "te/te_IN/padmavathi/medium/te_IN-padmavathi-medium.onnx",
                            localName = "te_IN-padmavathi-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "te/te_IN/padmavathi/medium/te_IN-padmavathi-medium.onnx.json",
                            localName = "te_IN-padmavathi-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-te_IN-venkatesh-medium",
                displayName = "Piper — Venkatesh (Telugu, India, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "te/te_IN/venkatesh/medium/te_IN-venkatesh-medium.onnx",
                            localName = "te_IN-venkatesh-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "te/te_IN/venkatesh/medium/te_IN-venkatesh-medium.onnx.json",
                            localName = "te_IN-venkatesh-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-tr_TR-dfki-medium",
                displayName = "Piper — Dfki (Turkish, Turkey, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx",
                            localName = "tr_TR-dfki-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx.json",
                            localName = "tr_TR-dfki-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-uk_UA-lada-x_low",
                displayName = "Piper — Lada (Ukrainian, Ukraine, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 21,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "uk/uk_UA/lada/x_low/uk_UA-lada-x_low.onnx",
                            localName = "uk_UA-lada-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "uk/uk_UA/lada/x_low/uk_UA-lada-x_low.onnx.json",
                            localName = "uk_UA-lada-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-uk_UA-mykyta-high",
                displayName = "Piper — Mykyta (Ukrainian, Ukraine, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "uk/uk_UA/mykyta/high/uk_UA-mykyta-high.onnx",
                            localName = "uk_UA-mykyta-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "uk/uk_UA/mykyta/high/uk_UA-mykyta-high.onnx.json",
                            localName = "uk_UA-mykyta-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-uk_UA-oleksa-high",
                displayName = "Piper — Oleksa (Ukrainian, Ukraine, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "uk/uk_UA/oleksa/high/uk_UA-oleksa-high.onnx",
                            localName = "uk_UA-oleksa-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "uk/uk_UA/oleksa/high/uk_UA-oleksa-high.onnx.json",
                            localName = "uk_UA-oleksa-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-uk_UA-tetiana-high",
                displayName = "Piper — Tetiana (Ukrainian, Ukraine, high)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 114,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "uk/uk_UA/tetiana/high/uk_UA-tetiana-high.onnx",
                            localName = "uk_UA-tetiana-high.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "uk/uk_UA/tetiana/high/uk_UA-tetiana-high.onnx.json",
                            localName = "uk_UA-tetiana-high.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-uk_UA-ukrainian_tts-medium",
                displayName = "Piper — Ukrainian Tts (Ukrainian, Ukraine, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 77,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "uk/uk_UA/ukrainian_tts/medium/uk_UA-ukrainian_tts-medium.onnx",
                            localName = "uk_UA-ukrainian_tts-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "uk/uk_UA/ukrainian_tts/medium/uk_UA-ukrainian_tts-medium.onnx.json",
                            localName = "uk_UA-ukrainian_tts-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ur_PK-aegis_female-medium",
                displayName = "Piper — Aegis Female (Urdu, Pakistan, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ur/ur_PK/aegis_female/medium/ur_PK-aegis_female-medium.onnx",
                            localName = "ur_PK-aegis_female-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ur/ur_PK/aegis_female/medium/ur_PK-aegis_female-medium.onnx.json",
                            localName = "ur_PK-aegis_female-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-ur_PK-fasih-medium",
                displayName = "Piper — Fasih (Urdu, Pakistan, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 64,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "ur/ur_PK/fasih/medium/ur_PK-fasih-medium.onnx",
                            localName = "ur_PK-fasih-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "ur/ur_PK/fasih/medium/ur_PK-fasih-medium.onnx.json",
                            localName = "ur_PK-fasih-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-vi_VN-25hours_single-low",
                displayName = "Piper — 25hours Single (Vietnamese, Vietnam, low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "vi/vi_VN/25hours_single/low/vi_VN-25hours_single-low.onnx",
                            localName = "vi_VN-25hours_single-low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "vi/vi_VN/25hours_single/low/vi_VN-25hours_single-low.onnx.json",
                            localName = "vi_VN-25hours_single-low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-vi_VN-vais1000-medium",
                displayName = "Piper — Vais1000 (Vietnamese, Vietnam, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "vi/vi_VN/vais1000/medium/vi_VN-vais1000-medium.onnx",
                            localName = "vi_VN-vais1000-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "vi/vi_VN/vais1000/medium/vi_VN-vais1000-medium.onnx.json",
                            localName = "vi_VN-vais1000-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-vi_VN-vivos-x_low",
                displayName = "Piper — Vivos (Vietnamese, Vietnam, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 28,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "vi/vi_VN/vivos/x_low/vi_VN-vivos-x_low.onnx",
                            localName = "vi_VN-vivos-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "vi/vi_VN/vivos/x_low/vi_VN-vivos-x_low.onnx.json",
                            localName = "vi_VN-vivos-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-zh_CN-chaowen-medium",
                displayName = "Piper — Chaowen (Chinese, China, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "zh/zh_CN/chaowen/medium/zh_CN-chaowen-medium.onnx",
                            localName = "zh_CN-chaowen-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "zh/zh_CN/chaowen/medium/zh_CN-chaowen-medium.onnx.json",
                            localName = "zh_CN-chaowen-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-zh_CN-huayan-medium",
                displayName = "Piper — Huayan (Chinese, China, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx",
                            localName = "zh_CN-huayan-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx.json",
                            localName = "zh_CN-huayan-medium.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-zh_CN-huayan-x_low",
                displayName = "Piper — Huayan (Chinese, China, extra-low)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 21,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "zh/zh_CN/huayan/x_low/zh_CN-huayan-x_low.onnx",
                            localName = "zh_CN-huayan-x_low.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "zh/zh_CN/huayan/x_low/zh_CN-huayan-x_low.onnx.json",
                            localName = "zh_CN-huayan-x_low.onnx.json",
                        ),
                    ),
            ),
            BuiltInModel(
                id = "piper-zh_CN-xiao_ya-medium",
                displayName = "Piper — Xiao Ya (Chinese, China, medium)",
                repoId = "rhasspy/piper-voices",
                approxSizeMb = 63,
                files =
                    listOf(
                        BuiltInFile(
                            repoPath = "zh/zh_CN/xiao_ya/medium/zh_CN-xiao_ya-medium.onnx",
                            localName = "zh_CN-xiao_ya-medium.onnx",
                        ),
                        BuiltInFile(
                            repoPath = "zh/zh_CN/xiao_ya/medium/zh_CN-xiao_ya-medium.onnx.json",
                            localName = "zh_CN-xiao_ya-medium.onnx.json",
                        ),
                    ),
            ),
        )
}
