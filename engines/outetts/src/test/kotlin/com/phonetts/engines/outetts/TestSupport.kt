package com.phonetts.engines.outetts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.testing.FakeNativeTtsSession
import com.phonetts.core.testing.FakePhonemizer

// Shared scaffolding for this module's tests only. Mirrors engines/ggmltts's TestSupport.kt (the
// closest template): this engine's runtime is also the non-ONNX NativeTtsRuntime, so it builds
// its own EngineContext rather than reuse engines.common's ONNX single-runtime helper.

/**
 * A single-voice bundle: one `<llmId>.gguf` + manifest naming [decoder]/[decoderFile]/[license],
 * plus one `<voiceId>.speaker.json` profile, plus the decoder gguf itself so the manifest's
 * `decoder_file` reference resolves. [LLM_ID]/[VOICE_ID]/[SAMPLE_RATE] are fixed - no test needs to
 * vary them - so they are plain constants rather than params (keeps this under detekt's
 * LongParameterList threshold; see `engines/outetts/INTEGRATION.md`).
 */
internal fun validBundle(
    id: String = "outetts-voice",
    decoder: String = "dac",
    decoderFile: String = "dac-speech-v1.0.gguf",
    license: String = "Apache-2.0",
): ModelBundle {
    val ggufFile = "$LLM_ID.gguf"
    val manifestFile = "$ggufFile.json"
    val speakerFile = "$VOICE_ID.speaker.json"
    val manifest =
        """{"decoder":"$decoder","decoder_file":"$decoderFile","sample_rate":$SAMPLE_RATE,"license":"$license"}"""
    return ModelBundle(
        id = id,
        fileNames = setOf(ggufFile, manifestFile, decoderFile, speakerFile),
        sideFiles = mapOf(manifestFile to manifest),
        rootPath = "/models/$id",
    )
}

/**
 * A multi-voice bundle: one LLM+decoder, several `<voiceId>.speaker.json` profiles. The decoder/
 * license facts are fixed here too - only [voiceIds] varies across call sites.
 */
internal fun multiVoiceBundle(
    id: String = "outetts-pack",
    voiceIds: List<String> = listOf("en-female-1-neutral", "en-male-1-neutral"),
): ModelBundle {
    val decoder = "dac"
    val decoderFile = "dac-speech-v1.0.gguf"
    val license = "Apache-2.0"
    val ggufFile = "$LLM_ID.gguf"
    val manifestFile = "$ggufFile.json"
    val manifest =
        """{"decoder":"$decoder","decoder_file":"$decoderFile","sample_rate":$SAMPLE_RATE,"license":"$license"}"""
    val fileNames = mutableSetOf(ggufFile, manifestFile, decoderFile)
    voiceIds.forEach { fileNames += "$it.speaker.json" }
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
 * [RecordingNativeTtsRuntime] under [OuteTtsEngine.NATIVE_RUNTIME_ID].
 */
internal fun contextWith(runtime: RecordingNativeTtsRuntime): EngineContext =
    EngineContext(
        runtimes = RuntimeRegistry().apply { register(runtime) },
        phonemizer = FakePhonemizer(),
    )

/**
 * A [RecordingNativeTtsRuntime] under this engine's native id, its session voicing each request
 * via [audioFor] and reporting [voiceNames] as the profiles the native side loaded.
 */
internal fun outeTtsRuntime(
    available: Boolean = true,
    voiceNames: List<String> = listOf("en-female-1-neutral"),
    audioFor: (NativeTtsRequest) -> FloatArray = { FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { 0.1f } },
): RecordingNativeTtsRuntime =
    RecordingNativeTtsRuntime(
        id = OuteTtsEngine.NATIVE_RUNTIME_ID,
        available = available,
        sessionFactory = { FakeNativeTtsSession(voiceNames = voiceNames, audioFor = audioFor) },
    )

/**
 * A local double, not reused from `:core`'s `testFixtures` [com.phonetts.core.testing.FakeNativeTtsRuntime]
 * because that fixture does not record the [RuntimeOptions] each `openTtsSession` call receives -
 * and part of this module's point is proving the discovered decoder type + decoder file name are
 * threaded through [RuntimeOptions.extras] (see [OuteTtsEngine.DECODER_TYPE_OPTION_KEY]/
 * [OuteTtsEngine.DECODER_FILE_OPTION_KEY]), so the test double must capture it.
 */
internal class RecordingNativeTtsRuntime(
    override val id: String = OuteTtsEngine.NATIVE_RUNTIME_ID,
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

// Fixed facts every validBundle()/multiVoiceBundle() call shares - not params (see their KDoc).
private const val LLM_ID = "outetts-1.0-0.6b-q4_k_m"
private const val VOICE_ID = "en-female-1-neutral"
private const val SAMPLE_RATE = 24_000
