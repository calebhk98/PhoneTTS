package com.phonetts.engines.ggmltts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.testing.FakeNativeTtsSession
import com.phonetts.core.testing.FakePhonemizer

// Shared scaffolding for this module's tests only. Mirrors engines/cosyvoice2's TestSupport.kt
// (the closest template): this engine's runtime is also the non-ONNX NativeTtsRuntime, so it
// builds its own EngineContext rather than reuse engines.common's ONNX single-runtime helper.

/** A single-voice bundle: one `<voiceId>.gguf` + manifest naming [backend]/[sampleRate]. */
internal fun validBundle(
    id: String = "ggml-piper-voice",
    voiceId: String = "en_US-lessac-medium",
    backend: String = "piper",
    sampleRate: Int = 22_050,
    language: String = "en",
): ModelBundle {
    val ggufFile = "$voiceId.gguf"
    val manifestFile = "$ggufFile.json"
    val manifest = """{"backend":"$backend","sample_rate":$sampleRate,"language":"$language"}"""
    return ModelBundle(
        id = id,
        fileNames = setOf(ggufFile, manifestFile),
        sideFiles = mapOf(manifestFile to manifest),
        rootPath = "/models/$id",
    )
}

/** A multi-voice bundle where every `<voiceId>.gguf` shares the same [backend]/[sampleRate]. */
internal fun multiVoiceBundle(
    id: String = "ggml-piper-pack",
    voiceIds: List<String> = listOf("en_US-lessac-medium", "en_US-amy-low"),
    backend: String = "piper",
    sampleRate: Int = 22_050,
): ModelBundle {
    val fileNames = mutableSetOf<String>()
    val sideFiles = mutableMapOf<String, String>()
    voiceIds.forEach { voiceId ->
        val ggufFile = "$voiceId.gguf"
        val manifestFile = "$ggufFile.json"
        fileNames += ggufFile
        fileNames += manifestFile
        sideFiles[manifestFile] = """{"backend":"$backend","sample_rate":$sampleRate,"language":"en"}"""
    }
    return ModelBundle(id = id, fileNames = fileNames, sideFiles = sideFiles, rootPath = "/models/$id")
}

/** An [EngineContext] with no runtime registered - enough to exercise inspect()/forcedMatch(). */
internal fun emptyContext(): EngineContext = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

/**
 * An [EngineContext] wired with the one runtime this engine uses: the non-ONNX
 * [RecordingNativeTtsRuntime] under [GgmlTtsEngine.NATIVE_RUNTIME_ID].
 */
internal fun contextWith(runtime: RecordingNativeTtsRuntime): EngineContext =
    EngineContext(
        runtimes = RuntimeRegistry().apply { register(runtime) },
        phonemizer = FakePhonemizer(),
    )

/**
 * A [RecordingNativeTtsRuntime] under this engine's native id, its session voicing each request
 * via [audioFor] and reporting [voiceNames] as the backend's baked voices.
 */
internal fun ggmlRuntime(
    available: Boolean = true,
    voiceNames: List<String> = listOf("en_US-lessac-medium"),
    audioFor: (NativeTtsRequest) -> FloatArray = { FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { 0.1f } },
): RecordingNativeTtsRuntime =
    RecordingNativeTtsRuntime(
        id = GgmlTtsEngine.NATIVE_RUNTIME_ID,
        available = available,
        sessionFactory = { FakeNativeTtsSession(voiceNames = voiceNames, audioFor = audioFor) },
    )

/**
 * A local double, not reused from `:core`'s `testFixtures` [com.phonetts.core.testing.FakeNativeTtsRuntime]
 * because that fixture does not record the [RuntimeOptions] each `openTtsSession` call receives -
 * and this module's whole point is proving the discovered CrispASR backend id is threaded through
 * [RuntimeOptions.extras] (see [GgmlTtsEngine.BACKEND_OPTION_KEY]), so the test double must capture it.
 */
internal class RecordingNativeTtsRuntime(
    override val id: String = GgmlTtsEngine.NATIVE_RUNTIME_ID,
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
