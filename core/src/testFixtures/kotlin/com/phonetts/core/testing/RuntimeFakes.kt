package com.phonetts.core.testing

import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Runtime
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.runtime.SpeechTokenRequest
import com.phonetts.core.runtime.SpeechTokenRuntime
import com.phonetts.core.runtime.SpeechTokenSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.text.Phonemizer

// Shared inference-seam doubles so every engine module can test its inspect()/speed-routing
// seams on a plain JVM, with no native runtime. Reuse via
// testImplementation(testFixtures(project(":core"))).

/**
 * Records every `run` it is given (so a test can assert the engine placed the right native
 * speed parameter, spec §9.4) and returns [outputs] — configure these to whatever named output
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
 * Records every [SpeechTokenRequest] it decodes (so a test can assert the engine routed the right
 * native speed/embedding, spec §9.4) and returns tokens from [tokensFor] — configure these to the
 * speech token ids the engine under test then feeds to its flow-matching graph.
 */
class FakeSpeechTokenSession(
    private val tokensFor: (SpeechTokenRequest) -> LongArray = { longArrayOf(0L) },
) : SpeechTokenSession {
    val requests = mutableListOf<SpeechTokenRequest>()
    var closed = false
        private set

    override fun generate(request: SpeechTokenRequest): LongArray {
        requests.add(request)
        return tokensFor(request)
    }

    override fun close() {
        closed = true
    }
}

/**
 * A [SpeechTokenRuntime] (the non-ONNX, LLM-style seam) that hands out [FakeSpeechTokenSession]s
 * and records the model paths it was asked to load. [available] lets a test simulate the native
 * GGUF backend being absent (the `-PwithCosyVoice` off case).
 */
class FakeSpeechTokenRuntime(
    override val id: String = "fake-llm",
    private val available: Boolean = true,
    private val sessionFactory: (String) -> FakeSpeechTokenSession = { FakeSpeechTokenSession() },
) : SpeechTokenRuntime {
    val createdPaths = mutableListOf<String>()
    val sessions = mutableListOf<FakeSpeechTokenSession>()

    override fun isAvailable(): Boolean = available

    override fun openSpeechSession(
        modelPath: String,
        options: RuntimeOptions,
    ): SpeechTokenSession {
        createdPaths.add(modelPath)
        val session = sessionFactory(modelPath)
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
