package com.phonetts.engines.piper

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * §9.1 fail-closed coverage for [PiperEngine.inspect]: a Piper bundle is only ever a `*.onnx`
 * WITH its `*.onnx.json` sidecar carrying Piper's own fields (phoneme_id_map, audio.sample_rate).
 * A bare `.onnx`, a foreign bundle, or an onnx+json pair whose json is not a Piper sidecar must
 * all come back null so the resolver falls through to the user-pick fallback — never a guess.
 */
class PiperEngineInspectTest {
    private val engine = PiperEngine(engineContext())

    private val validSidecar =
        """
        {
          "audio": {"sample_rate": 22050},
          "espeak": {"voice": "en-us"},
          "phoneme_id_map": {"^": [1], "$": [2], "_": [0], "h": [10], "i": [11]},
          "inference": {"noise_scale": 0.667, "length_scale": 1.0, "noise_w": 0.8}
        }
        """.trimIndent()

    @Test
    fun `claims an onnx file that has a valid piper sidecar and populates the descriptor`() {
        val bundle =
            ModelBundle(
                id = "en_US-amy-medium",
                fileNames = setOf("voice.onnx", "voice.onnx.json"),
                sideFiles = mapOf("voice.onnx.json" to validSidecar),
                rootPath = "/models/en_US-amy-medium",
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals("piper", match.engineId)
        val descriptor = match.descriptor
        assertEquals("piper", descriptor.engineId)
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(22_050, descriptor.sampleRate)
        assertEquals(listOf("voice"), descriptor.voices.map { it.id })
        assertEquals("en-us", descriptor.voices.single().language)
        assertEquals("/models/en_US-amy-medium/voice.onnx", descriptor.assetPaths["voice.onnx"])
        assertEquals("/models/en_US-amy-medium/voice.onnx.json", descriptor.assetPaths["voice.onnx.json"])
    }

    @Test
    fun `returns null for a bare onnx with no sidecar at all`() {
        val bundle = ModelBundle(id = "bare", fileNames = setOf("voice.onnx"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `returns null when the onnx has a sidecar but it lacks piper fields`() {
        val bundle =
            ModelBundle(
                id = "foreign-pair",
                fileNames = setOf("voice.onnx", "voice.onnx.json"),
                sideFiles = mapOf("voice.onnx.json" to """{"totally_unrelated": true}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `returns null for a foreign bundle with no onnx file at all`() {
        val bundle =
            ModelBundle(
                id = "some-other-model",
                fileNames = setOf("model.bin", "config.json"),
                sideFiles = mapOf("config.json" to """{"family": "not-piper"}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `returns null when the sidecar json is malformed`() {
        val bundle =
            ModelBundle(
                id = "broken-json",
                fileNames = setOf("voice.onnx", "voice.onnx.json"),
                sideFiles = mapOf("voice.onnx.json" to "{not valid json"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch never returns null and fills in family defaults for a bare onnx`() {
        val bundle = ModelBundle(id = "sideloaded", fileNames = setOf("mystery.onnx"), rootPath = "/models/sideloaded")

        val match = engine.forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(22_050, match.descriptor.sampleRate)
        assertEquals(listOf("mystery"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `forcedMatch throws when the bundle has no onnx weights at all`() {
        val bundle = ModelBundle(id = "no-weights", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }

    // issue #95: speaches-ai/*, ufozone/*, and Lucasllfs/Razo-piper-voice all ship a valid Piper
    // sidecar under the plain name "config.json" instead of "<voice>.onnx.json".
    @Test
    fun `claims a single onnx paired with a plain config json sidecar`() {
        val bundle =
            ModelBundle(
                id = "speaches-ai-voice",
                fileNames = setOf("voice.onnx", "config.json"),
                sideFiles = mapOf("config.json" to validSidecar),
                rootPath = "/models/speaches-ai-voice",
            )

        val match = assertNotNull(engine.inspect(bundle))

        val descriptor = match.descriptor
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(22_050, descriptor.sampleRate)
        assertEquals(listOf("voice"), descriptor.voices.map { it.id })
        assertEquals("/models/speaches-ai-voice/voice.onnx", descriptor.assetPaths["voice.onnx"])
        assertEquals("/models/speaches-ai-voice/config.json", descriptor.assetPaths["voice.onnx.json"])
    }

    @Test
    fun `returns null when the single onnx's config json is not piper shaped`() {
        val bundle =
            ModelBundle(
                id = "foreign-config-json",
                fileNames = setOf("voice.onnx", "config.json"),
                sideFiles = mapOf("config.json" to """{"totally_unrelated": true}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    // ayousanz/piper-plus-* also ships a Piper-shaped config.json but needs extra
    // language_id/prosody inputs this engine does not feed — out of scope for issue #95, but this
    // pins the SAME rejection a foreign config.json gets, since [PiperVoiceConfig.parse] can't tell
    // the two apart from the sidecar alone. Named separately so a future piper-plus fix updates the
    // right expectation, not this one.
    @Test
    fun `does not falsely pair a stray config json across a multi onnx bundle`() {
        val bundle =
            ModelBundle(
                id = "multi-onnx-with-stray-config",
                fileNames = setOf("voice-a.onnx", "voice-b.onnx", "config.json"),
                sideFiles = mapOf("config.json" to validSidecar),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `still resolves the stem sidecar path when both stem json and a plain config json exist`() {
        val bundle =
            ModelBundle(
                id = "has-both-sidecars",
                fileNames = setOf("voice.onnx", "voice.onnx.json", "config.json"),
                sideFiles =
                    mapOf(
                        "voice.onnx.json" to validSidecar,
                        "config.json" to """{"totally_unrelated": true}""",
                    ),
                rootPath = "/models/has-both-sidecars",
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals("/models/has-both-sidecars/voice.onnx.json", match.descriptor.assetPaths["voice.onnx.json"])
    }

    // issue #110: a piper-plus multilingual sidecar is Piper-shaped (audio.sample_rate +
    // phoneme_id_map) but declares language_id/prosody inputs this engine never feeds, so its graph
    // crashes at session.run. inspect() must fail closed on the markers rather than claim-then-crash.
    private val piperPlusMultilingualSidecar =
        """
        {
          "audio": {"sample_rate": 22050},
          "espeak": {"voice": "ja-en-zh-es-fr-pt"},
          "phoneme_type": "multilingual",
          "phoneme_id_map": {"_": [0], "^": [1], "$": [2], "a": [10]},
          "inference": {"noise_scale": 0.667, "length_scale": 1, "noise_w": 0.8},
          "num_speakers": 1,
          "num_languages": 6,
          "language_id_map": {"ja": 0, "en": 1, "zh": 2, "es": 3, "fr": 4, "pt": 5},
          "prosody_num_symbols": 11,
          "prosody_id_map": {"0": [0], "1": [1]}
        }
        """.trimIndent()

    @Test
    fun `returns null for a piper-plus multilingual onnx-json sidecar`() {
        val bundle =
            ModelBundle(
                id = "css10-ja-6lang",
                fileNames = setOf("css10-ja-6lang.onnx", "css10-ja-6lang.onnx.json"),
                sideFiles = mapOf("css10-ja-6lang.onnx.json" to piperPlusMultilingualSidecar),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `returns null for a piper-plus multilingual bundle shipping a plain config json`() {
        val bundle =
            ModelBundle(
                id = "piper-plus-base",
                fileNames = setOf("model.onnx", "config.json"),
                sideFiles = mapOf("config.json" to piperPlusMultilingualSidecar),
            )

        assertInspectRejects(engine, bundle)
    }

    // Only the prosody input marker present (no phoneme_type/num_languages) still fails closed —
    // proves the rejection is an OR over the piper-plus markers, not a single phoneme_type check.
    @Test
    fun `returns null when a sidecar declares only a prosody id map`() {
        val prosodyOnly =
            """
            {
              "audio": {"sample_rate": 22050},
              "espeak": {"voice": "en-us"},
              "phoneme_id_map": {"_": [0], "^": [1], "$": [2], "a": [10]},
              "prosody_id_map": {"0": [0], "1": [1]}
            }
            """.trimIndent()
        val bundle =
            ModelBundle(
                id = "prosody-only",
                fileNames = setOf("voice.onnx", "voice.onnx.json"),
                sideFiles = mapOf("voice.onnx.json" to prosodyOnly),
            )

        assertInspectRejects(engine, bundle)
    }

    // issue #110: an fp16 export names its precision in the file name; this engine always feeds
    // float32 scales + int64 ids, so an fp16 graph is a guaranteed dtype mismatch — reject at inspect
    // even though the sidecar itself is a perfectly valid Piper sidecar.
    @Test
    fun `returns null for an fp16 export even with a valid piper sidecar`() {
        val bundle =
            ModelBundle(
                id = "tsukuyomi-chan-6lang-fp16",
                fileNames = setOf("tsukuyomi-chan-6lang-fp16.onnx", "tsukuyomi-chan-6lang-fp16.onnx.json"),
                sideFiles = mapOf("tsukuyomi-chan-6lang-fp16.onnx.json" to validSidecar),
            )

        assertInspectRejects(engine, bundle)
    }

    // Guard against over-rejecting: a STANDARD multi-speaker Piper graph (num_speakers > 1 +
    // speaker_id_map, no multilingual/prosody markers) feeds only sid on top of the base contract,
    // which this engine already handles — so it must still be claimed.
    @Test
    fun `still claims a standard multi-speaker voice with no piper-plus markers`() {
        val multiSpeakerSidecar =
            """
            {
              "audio": {"sample_rate": 22050},
              "espeak": {"voice": "en-us"},
              "phoneme_type": "espeak",
              "phoneme_id_map": {"_": [0], "^": [1], "$": [2], "a": [10]},
              "num_speakers": 3,
              "speaker_id_map": {"p225": 0, "p226": 1, "p227": 2}
            }
            """.trimIndent()
        val bundle =
            ModelBundle(
                id = "en_US-libritts_r-medium",
                fileNames = setOf("voice.onnx", "voice.onnx.json"),
                sideFiles = mapOf("voice.onnx.json" to multiSpeakerSidecar),
                rootPath = "/models/libritts",
            )

        val match = assertNotNull(engine.inspect(bundle))
        assertEquals(listOf("voice#p225", "voice#p226", "voice#p227"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `forcedMatch also accepts a plain config json sidecar for a single onnx`() {
        val bundle =
            ModelBundle(
                id = "forced-config-json",
                fileNames = setOf("voice.onnx", "config.json"),
                sideFiles = mapOf("config.json" to validSidecar),
                rootPath = "/models/forced-config-json",
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(22_050, match.descriptor.sampleRate)
        assertEquals("/models/forced-config-json/config.json", match.descriptor.assetPaths["voice.onnx.json"])
    }
}
