package com.phonetts.core.testing

import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.core.runtime.Runtime
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.text.Phonemizer

// Shared inference-seam doubles so every engine module can test its inspect()/speed-routing
// seams on a plain JVM, with no native runtime. Reuse via
// testImplementation(testFixtures(project(":core"))).

/**
 * Records every `run` it is given (so a test can assert the engine placed the right native
 * speed parameter, spec §9.4) and returns [outputs] - configure these to whatever named output
 * tensor the engine under test post-processes into audio.
 */
class FakeSession(
    private val outputs: Map<String, Tensor> = emptyMap(),
    private val outputsFor: (Map<String, Tensor>) -> Map<String, Tensor> = { outputs },
) : InferenceSession {
    val runs = mutableListOf<Map<String, Tensor>>()
    var closed = false
        private set

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        runs.add(inputs)
        return outputsFor(inputs)
    }

    override fun close() {
        closed = true
    }
}

/** A [Runtime] that hands out [FakeSession]s and records the model paths it was asked to load. */
class FakeRuntime(
    override val id: String = "fake",
    private val sessionFactory: (String) -> FakeSession = { FakeSession() },
) : Runtime {
    val createdPaths = mutableListOf<String>()
    val sessions = mutableListOf<FakeSession>()

    override fun isAvailable(): Boolean = true

    override fun createSession(
        modelPath: String,
        options: RuntimeOptions,
    ): InferenceSession {
        createdPaths.add(modelPath)
        val session = sessionFactory(modelPath)
        sessions.add(session)
        return session
    }
}

/**
 * Records every [NativeTtsRequest] it synthesizes (so a test can assert the engine handed the right
 * text + voice to the native pipeline) and returns audio from [audioFor] - configure these to the
 * PCM the engine under test emits as one sentence's chunk. [voiceNames]/[sampleRate] stand in for
 * what the real backend reads from the model's voices GGUF.
 */
class FakeNativeTtsSession(
    override val sampleRate: Int = 24_000,
    override val voiceNames: List<String> = listOf("zero_shot"),
    private val audioFor: (NativeTtsRequest) -> FloatArray = { floatArrayOf(0.1f, -0.1f) },
) : NativeTtsSession {
    val requests = mutableListOf<NativeTtsRequest>()
    var closed = false
        private set

    override fun synthesize(request: NativeTtsRequest): FloatArray {
        requests.add(request)
        return audioFor(request)
    }

    override fun close() {
        closed = true
    }
}

/**
 * A [NativeTtsRuntime] (the non-ONNX, full-pipeline seam) that hands out [FakeNativeTtsSession]s and
 * records the model directories it was asked to load. [available] lets a test simulate the native
 * ggml backend being absent (the `-PwithCosyVoice` off case).
 */
class FakeNativeTtsRuntime(
    override val id: String = "fake-cosyvoice",
    private val available: Boolean = true,
    private val sessionFactory: (String) -> FakeNativeTtsSession = { FakeNativeTtsSession() },
) : NativeTtsRuntime {
    val createdDirs = mutableListOf<String>()
    val sessions = mutableListOf<FakeNativeTtsSession>()

    override fun isAvailable(): Boolean = available

    override fun openTtsSession(
        modelDir: String,
        options: RuntimeOptions,
    ): NativeTtsSession {
        createdDirs.add(modelDir)
        val session = sessionFactory(modelDir)
        sessions.add(session)
        return session
    }
}

/** A [Phonemizer] whose mapping the test controls; defaults to identity. */
class FakePhonemizer(
    private val mapping: (String) -> String = { it },
) : Phonemizer {
    val calls = mutableListOf<Pair<String, String>>()

    override fun phonemize(
        text: String,
        language: String,
    ): String {
        calls.add(text to language)
        return mapping(text)
    }
}
