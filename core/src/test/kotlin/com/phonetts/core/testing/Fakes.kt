package com.phonetts.core.testing

import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared test fixtures for the seam tests. A configurable [FakeEngine] plus a descriptor
 * builder so registry/resolver/audio tests can construct realistic inputs with no real model.
 */

/** Build a valid [ModelDescriptor] for tests without hand-writing every field. */
fun testDescriptor(
    modelId: String,
    engineId: String,
    sampleRate: Int = 22_050,
    voices: List<Voice> = listOf(Voice("v0", "Voice 0", "en")),
    speedRange: ClosedFloatingPointRange<Float> = 0.5f..2.0f,
    origin: Origin = Origin.BUILT_IN,
): ModelDescriptor =
    ModelDescriptor(
        modelId = modelId,
        engineId = engineId,
        displayName = "Model $modelId",
        origin = origin,
        sampleRate = sampleRate,
        voices = voices,
        speedRange = speedRange,
        defaultVoiceId = voices.first().id,
        defaultSpeed = 1.0f.coerceIn(speedRange),
    )

/**
 * A fully configurable [VoiceEngine] double.
 *
 *  - [claims]        controls whether [inspect] claims a given bundle (fail-closed default: false).
 *  - [descriptorFor] builds the descriptor an inspect/forced match returns.
 *  - [eventLog]      optional shared log so tests can assert cross-engine ordering, e.g. that
 *                    the manager calls `unload` on the old engine before `load` on the new one.
 */
class FakeEngine(
    override val id: String,
    override val displayName: String = "Fake $id",
    private val claims: (ModelBundle) -> Boolean = { false },
    private val descriptorFor: (ModelBundle) -> ModelDescriptor = { testDescriptor(it.id, id) },
    private val voiceList: List<Voice> = listOf(Voice("v0", "Voice 0", "en")),
    private val audio: List<FloatArray> = listOf(floatArrayOf(0f, 0.1f, -0.1f)),
    private val eventLog: MutableList<String>? = null,
) : VoiceEngine {
    var inspectCount = 0
        private set
    var loadCount = 0
        private set
    var unloadCount = 0
        private set
    var lastSpeed: Float? = null
        private set
    var lastVoiceId: String? = null
        private set

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        inspectCount++
        if (!claims(bundle)) return null
        return EngineMatch(id, descriptorFor(bundle))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch = EngineMatch(id, descriptorFor(bundle))

    override suspend fun load(descriptor: ModelDescriptor) {
        loadCount++
        eventLog?.add("load:$id")
    }

    override fun unload() {
        unloadCount++
        eventLog?.add("unload:$id")
    }

    override fun voices(): List<Voice> = voiceList

    override fun synthesize(text: String, voiceId: String, speed: Float): Flow<FloatArray> {
        lastSpeed = speed
        lastVoiceId = voiceId
        return flowOf(*audio.toTypedArray())
    }
}
