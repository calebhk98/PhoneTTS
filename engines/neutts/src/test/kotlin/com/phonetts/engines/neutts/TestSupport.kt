package com.phonetts.engines.neutts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.testing.FakeNativeTtsSession
import com.phonetts.core.testing.FakePhonemizer

// Shared scaffolding for this module's tests only. Mirrors engines/ggmltts's TestSupport.kt (this
// module's closest template): this engine's runtime is also the non-ONNX NativeTtsRuntime, so it
// builds its own EngineContext rather than reuse engines.common's ONNX single-runtime helper.

/**
 * A single-voice bundle: one `<ggufFile>` backbone + `<ggufFile>.json` manifest naming
 * [sampleRate]/[inputFormat]/[codecDecoderFile] and one `(ref_audio, ref_text)` clone-voice entry.
 */
internal fun validBundle(
    sampleRate: Int = 24_000,
    inputFormat: String = "phonemes",
    codecDecoderFile: String = "neucodec-decoder.onnx",
    voiceId: String = "dave",
    refAudioFile: String = "dave.wav",
): ModelBundle {
    // Never overridden by any test - kept as internal vals (not params) to stay under detekt's
    // LongParameterList threshold (7+) while leaving every default value/behavior unchanged.
    val id = "neutts-nano-voice"
    val ggufFile = "neutts-nano-Q4_0.gguf"
    val voiceName = "Dave"
    val language = "en"
    val refText = "My name is Dave, and um, I'm from London."
    val manifestFile = "$ggufFile.json"
    val manifest =
        """
        {"sample_rate":$sampleRate,"input_format":"$inputFormat","codec_decoder":"$codecDecoderFile",
         "voices":[{"id":"$voiceId","name":"$voiceName","language":"$language",
         "ref_audio":"$refAudioFile","ref_text":"$refText"}]}
        """.trimIndent()
    return ModelBundle(
        id = id,
        fileNames = setOf(ggufFile, manifestFile, codecDecoderFile, refAudioFile),
        sideFiles = mapOf(manifestFile to manifest),
        rootPath = "/models/$id",
    )
}

/** A multi-voice bundle: one backbone `.gguf`, one manifest, several `(id, ref_audio, ref_text)` voices. */
internal fun multiVoiceBundle(
    id: String = "neutts-nano-multi",
    ggufFile: String = "neutts-nano-Q4_0.gguf",
    sampleRate: Int = 24_000,
    inputFormat: String = "phonemes",
    codecDecoderFile: String = "neucodec-decoder.onnx",
    voices: List<Triple<String, String, String>> =
        listOf(
            Triple("dave", "dave.wav", "My name is Dave, and um, I'm from London."),
            Triple("jo", "jo.wav", "Hi there, I'm Jo."),
        ),
): ModelBundle {
    val manifestFile = "$ggufFile.json"
    val voicesJson =
        voices.joinToString(",") { (voiceId, refAudio, refText) ->
            """{"id":"$voiceId","ref_audio":"$refAudio","ref_text":"$refText"}"""
        }
    val manifest =
        """{"sample_rate":$sampleRate,"input_format":"$inputFormat","codec_decoder":"$codecDecoderFile",""" +
            """"voices":[$voicesJson]}"""
    val fileNames = mutableSetOf(ggufFile, manifestFile, codecDecoderFile)
    voices.forEach { (_, refAudio, _) -> fileNames += refAudio }
    return ModelBundle(
        id = id,
        fileNames = fileNames,
        sideFiles = mapOf(manifestFile to manifest),
        rootPath = "/models/$id",
    )
}

/** An [EngineContext] with no runtime registered - enough to exercise inspect()/forcedMatch(). */
internal fun emptyContext(): EngineContext = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

/**
 * An [EngineContext] wired with the one runtime this engine uses: the non-ONNX
 * [RecordingNativeTtsRuntime] under [NeuTtsEngine.NATIVE_RUNTIME_ID], plus [phonemizer] so tests can
 * assert phonemization was (or wasn't) invoked per the discovered `input_format`.
 */
internal fun contextWith(
    runtime: RecordingNativeTtsRuntime,
    phonemizer: FakePhonemizer = FakePhonemizer(),
): EngineContext =
    EngineContext(
        runtimes = RuntimeRegistry().apply { register(runtime) },
        phonemizer = phonemizer,
    )

/**
 * A [RecordingNativeTtsRuntime] under this engine's native id, its session voicing each request via
 * [audioFor] and reporting [voiceNames] as the voices its (fake) reference-clip encoding baked.
 */
internal fun neuTtsRuntime(
    available: Boolean = true,
    voiceNames: List<String> = listOf("dave"),
    audioFor: (NativeTtsRequest) -> FloatArray = { FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { 0.1f } },
): RecordingNativeTtsRuntime =
    RecordingNativeTtsRuntime(
        id = NeuTtsEngine.NATIVE_RUNTIME_ID,
        available = available,
        sessionFactory = { FakeNativeTtsSession(voiceNames = voiceNames, audioFor = audioFor) },
    )

/**
 * A local double, not reused from `:core`'s `testFixtures` [com.phonetts.core.testing.FakeNativeTtsRuntime]
 * because that fixture does not record the [RuntimeOptions] each `openTtsSession` call receives - this
 * module's whole point is proving the discovered codec-decoder path is threaded through
 * [RuntimeOptions.extras] (see [NeuTtsEngine.CODEC_DECODER_OPTION_KEY]), so the test double must
 * capture it.
 */
internal class RecordingNativeTtsRuntime(
    override val id: String = NeuTtsEngine.NATIVE_RUNTIME_ID,
    private val available: Boolean = true,
    private val sessionFactory: (String) -> FakeNativeTtsSession = { FakeNativeTtsSession() },
) : NativeTtsRuntime {
    val createdDirs = mutableListOf<String>()
    val optionsSeen = mutableListOf<RuntimeOptions>()
    val sessions = mutableListOf<FakeNativeTtsSession>()

    override fun isAvailable(): Boolean = available

    override fun openTtsSession(
        modelDir: String,
        options: RuntimeOptions,
    ): NativeTtsSession {
        createdDirs.add(modelDir)
        optionsSeen.add(options)
        val session = sessionFactory(modelDir)
        sessions.add(session)
        return session
    }
}

internal const val DEFAULT_SAMPLES_PER_SENTENCE = 1280
