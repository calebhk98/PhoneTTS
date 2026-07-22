package com.phonetts.core.download.builtin

import com.phonetts.core.download.SafePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PiperVoicesIndex] replaces the old hand-generated `PiperVoiceCatalog` snapshot: instead of a
 * checked-in list, it parses upstream rhasspy/piper-voices' own `voices.json` at runtime. These
 * tests inline a small (3-voice) fixture — a verbatim slice of the real upstream manifest,
 * including the extra fields (`aliases`, `num_speakers`, `speaker_id_map`, a `MODEL_CARD` file
 * entry, non-ASCII `name_native`) the parser must tolerate via `ignoreUnknownKeys` — so no network
 * call happens in a test (CLAUDE.md: :core tests can't use network).
 */
class PiperVoicesIndexTest {
    @Test
    fun parsesEachVoiceIntoABuiltInModelWithTheRealOnnxAndSidecarPaths() {
        val voices = PiperVoicesIndex.parse(THREE_VOICE_FIXTURE)
        assertEquals(3, voices.size)

        val lessac = voices.single { it.id == "piper-en_US-lessac-medium" }
        assertEquals("Piper — Lessac (English, United States, medium)", lessac.displayName)
        assertEquals("rhasspy/piper-voices", lessac.repoId)
        // 63201294 bytes, decimal MB rounded.
        assertEquals(63, lessac.approxSizeMb)
        assertEquals(
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
            lessac.files,
        )
    }

    @Test
    fun mapsExtraLowQualityToItsFriendlyDisplayLabel() {
        val voices = PiperVoicesIndex.parse(THREE_VOICE_FIXTURE)
        val ona = voices.single { it.id == "piper-ca_ES-upc_ona-x_low" }
        assertEquals("Piper — Upc Ona (Catalan, Spain, extra-low)", ona.displayName)
        assertEquals(21, ona.approxSizeMb) // 20628813 bytes -> 20.6 -> rounds to 21
    }

    @Test
    fun toleratesUnknownFieldsAndNonAsciiNativeNames() {
        // bn_BD-google-medium's fixture entry carries num_speakers/speaker_id_map (a 16-entry map)
        // and a non-ASCII language.name_native ("বাংলা") — neither is modeled, both must be
        // ignored rather than failing the whole parse.
        val voices = PiperVoicesIndex.parse(THREE_VOICE_FIXTURE)
        val google = voices.single { it.id == "piper-bn_BD-google-medium" }
        assertEquals("Piper — Google (Bengali, Bangladesh, medium)", google.displayName)
        assertEquals(77, google.approxSizeMb)
    }

    @Test
    fun everyParsedVoiceHasSafeUniqueIdentifiersAndExactlyTwoFiles() {
        val voices = PiperVoicesIndex.parse(THREE_VOICE_FIXTURE)
        val ids = voices.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids")

        voices.forEach { voice ->
            assertTrue(voice.id.startsWith("piper-"))
            assertEquals(2, voice.files.size)
            voice.files.forEach { file ->
                assertTrue(SafePath.isSafe(file.localName), "${voice.id}: unsafe local name")
                assertTrue(SafePath.isSafe(file.repoPath), "${voice.id}: unsafe repo path")
            }
        }
    }

    @Test
    fun skipsAnEntryMissingItsOnnxFileInsteadOfFailingTheWholeParse() {
        // Fail-closed on a per-entry basis: one malformed/incomplete upstream entry must not take
        // down the whole browsable list.
        val voices = PiperVoicesIndex.parse(FIXTURE_WITH_ONE_MISSING_ONNX)
        assertEquals(1, voices.size)
        assertEquals("piper-en_US-lessac-medium", voices.single().id)
    }

    @Test
    fun throwsOnGenuinelyInvalidJsonSoTheCallerCanFailClosed() {
        assertFailsWith<Exception> { PiperVoicesIndex.parse("not json") }
    }

    @Test
    fun emptyManifestParsesToAnEmptyList() {
        val voices = PiperVoicesIndex.parse("{}")
        assertNull(voices.firstOrNull())
    }

    private companion object {
        // A verbatim 3-voice slice of upstream rhasspy/piper-voices' voices.json (fetched from
        // https://huggingface.co/rhasspy/piper-voices/resolve/main/voices.json), trimmed to the
        // fields PiperVoicesIndex reads plus a few it must ignore.
        val THREE_VOICE_FIXTURE =
            """
            {
              "en_US-lessac-medium": {
                "key": "en_US-lessac-medium",
                "name": "lessac",
                "language": {
                  "code": "en_US",
                  "family": "en",
                  "region": "US",
                  "name_native": "English",
                  "name_english": "English",
                  "country_english": "United States"
                },
                "quality": "medium",
                "num_speakers": 1,
                "speaker_id_map": {},
                "files": {
                  "en/en_US/lessac/medium/en_US-lessac-medium.onnx": {
                    "size_bytes": 63201294,
                    "md5_digest": "2fc642b535197b6305c7c8f92dc8b24f"
                  },
                  "en/en_US/lessac/medium/en_US-lessac-medium.onnx.json": {
                    "size_bytes": 4885,
                    "md5_digest": "c1f2b7bddefe113f3255ff9ef234cfd3"
                  },
                  "en/en_US/lessac/medium/MODEL_CARD": {
                    "size_bytes": 351,
                    "md5_digest": "42f2dd4a98149e12fc70b301d9579dfd"
                  }
                },
                "aliases": ["en-us-lessac-medium"]
              },
              "ca_ES-upc_ona-x_low": {
                "key": "ca_ES-upc_ona-x_low",
                "name": "upc_ona",
                "language": {
                  "code": "ca_ES",
                  "family": "ca",
                  "region": "ES",
                  "name_native": "Català",
                  "name_english": "Catalan",
                  "country_english": "Spain"
                },
                "quality": "x_low",
                "num_speakers": 1,
                "speaker_id_map": {},
                "files": {
                  "ca/ca_ES/upc_ona/x_low/ca_ES-upc_ona-x_low.onnx": {
                    "size_bytes": 20628813,
                    "md5_digest": "ca22734cd8c5b01dd1fefbb42067ab06"
                  },
                  "ca/ca_ES/upc_ona/x_low/ca_ES-upc_ona-x_low.onnx.json": {
                    "size_bytes": 4159,
                    "md5_digest": "82ccdadad1c203feaff8f77aef9087a3"
                  },
                  "ca/ca_ES/upc_ona/x_low/MODEL_CARD": {
                    "size_bytes": 258,
                    "md5_digest": "1f555643ff6f7d9133679d730f3f6016"
                  }
                },
                "aliases": ["ca-upc_ona-x-low"]
              },
              "bn_BD-google-medium": {
                "key": "bn_BD-google-medium",
                "name": "google",
                "language": {
                  "code": "bn_BD",
                  "family": "bn",
                  "region": "BD",
                  "name_native": "বাংলা",
                  "name_english": "Bengali",
                  "country_english": "Bangladesh"
                },
                "quality": "medium",
                "num_speakers": 16,
                "speaker_id_map": {"00737": 0, "01232": 1, "rm": 15},
                "files": {
                  "bn/bn_BD/google/medium/bn_BD-google-medium.onnx": {
                    "size_bytes": 76782515,
                    "md5_digest": "2a365b2d91bb9cb7ed62c57d9ee0ec48"
                  },
                  "bn/bn_BD/google/medium/bn_BD-google-medium.onnx.json": {
                    "size_bytes": 5494,
                    "md5_digest": "2286795a555ad100657de86646a74f12"
                  },
                  "bn/bn_BD/google/medium/MODEL_CARD": {
                    "size_bytes": 2419,
                    "md5_digest": "1bf6e7d71fa4144b9b571aa4fde29ddc"
                  }
                },
                "aliases": []
              }
            }
            """.trimIndent()

        val FIXTURE_WITH_ONE_MISSING_ONNX =
            """
            {
              "en_US-lessac-medium": {
                "key": "en_US-lessac-medium",
                "name": "lessac",
                "language": {
                  "code": "en_US",
                  "family": "en",
                  "region": "US",
                  "name_native": "English",
                  "name_english": "English",
                  "country_english": "United States"
                },
                "quality": "medium",
                "files": {
                  "en/en_US/lessac/medium/en_US-lessac-medium.onnx": {"size_bytes": 63201294},
                  "en/en_US/lessac/medium/en_US-lessac-medium.onnx.json": {"size_bytes": 4885}
                }
              },
              "xx_XX-broken-medium": {
                "key": "xx_XX-broken-medium",
                "name": "broken",
                "language": {
                  "code": "xx_XX",
                  "family": "xx",
                  "region": "XX",
                  "name_native": "Broken",
                  "name_english": "Broken",
                  "country_english": "Nowhere"
                },
                "quality": "medium",
                "files": {
                  "xx/xx_XX/broken/medium/MODEL_CARD": {"size_bytes": 42}
                }
              }
            }
            """.trimIndent()
    }
}
