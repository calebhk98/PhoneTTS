package com.phonetts.app.runtime

import android.content.Context
import android.speech.tts.TextToSpeech
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.text.TextChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Adapts the on-device Android `TextToSpeech` engine(s) - Google, Samsung, or whichever else the
 * OS/vendor shipped - to the [VoiceEngine] contract (issue #77). Unlike every other engine in this
 * app, there are no weights to download: the "model" is whatever the OS already has installed, so
 * this engine is registered directly with the catalog by [com.phonetts.app.AppGraph] rather than
 * going through the bundle-on-disk resolver pipeline (see [SystemTtsDiscovery]).
 *
 * One instance of this class serves every installed system-TTS package - exactly the same shape as
 * Piper serving every voice inside one downloaded bundle. Each installed package gets its own
 * [ModelDescriptor] (same [id], different [ModelDescriptor.modelId] - see [modelIdFor]); switching
 * between "System - Google" and "System - Samsung" is an ordinary [load] call with a different
 * descriptor, so [EngineManager][com.phonetts.core.registry.EngineManager]'s one-engine-loaded-at-a-time
 * rule (CLAUDE.md rule 6) tears down the previous `android.speech.tts.TextToSpeech` binding first.
 *
 * `TextToSpeech` has no streaming PCM API - it is async (`OnInitListener`) and file-based
 * (`synthesizeToFile`), never handing back raw samples directly. This engine bridges that to the
 * app's one generation path (`Flow<FloatArray>`, CLAUDE.md rule 3) by, per sentence: writing a WAV
 * to a cache-dir temp file, reading that file back into float PCM, and emitting it - the same
 * "chunk by sentence so playback can start early" discipline every other engine follows (rule 8),
 * just implemented by hand here instead of via `AbstractVoiceEngine` (that class lives in
 * `engines/common`, a module `:app` deliberately never depends on - see its module's build.gradle -
 * so engines Android needs directly, like this one, are self-contained in `:app`).
 *
 * Speed maps straight onto `TextToSpeech.setSpeechRate` - its native rate knob (CLAUDE.md rule 2:
 * never resample for speed); output audio is never touched beyond the WAV→float decode.
 */
internal class SystemTtsEngine(private val appContext: Context) : VoiceEngine {
    override val id: String = ENGINE_ID
    override val displayName: String = "Device Text-to-Speech"

    @Volatile private var engine: TextToSpeech? = null

    @Volatile private var loadedDescriptor: ModelDescriptor? = null

    /**
     * This engine has no bundle-on-disk concept at all - its descriptors are built by
     * [SystemTtsDiscovery] and registered directly (see [com.phonetts.app.AppGraph.hydrate]), never
     * through [com.phonetts.core.sideload.ModelImporter]. So every bundle is "not mine" - fails
     * closed exactly per CLAUDE.md rule 4, just unconditionally rather than after inspecting content.
     */
    override fun inspect(bundle: ModelBundle): EngineMatch? = null

    /** There is nothing a user could sideload into this engine - see [inspect]'s kdoc. */
    override fun forcedMatch(bundle: ModelBundle): EngineMatch =
        throw UnsupportedOperationException(
            "$displayName has no downloadable bundle to sideload - it always uses whatever the OS provides",
        )

    override suspend fun load(descriptor: ModelDescriptor) {
        unload()
        val enginePackage = enginePackageFrom(descriptor.modelId)
        val initialized =
            SystemTtsInit.initialize(appContext, enginePackage)
                ?: error("$displayName: engine '$enginePackage' failed to initialize")
        engine = initialized
        loadedDescriptor = descriptor
    }

    override fun unload() {
        engine?.let { runCatching { it.shutdown() } }
        engine = null
        loadedDescriptor = null
    }

    override fun voices(): List<Voice> = loadedDescriptor?.voices ?: emptyList()

    override fun synthesize(
        text: String,
        voiceId: String,
        params: SynthesisParams,
    ): Flow<FloatArray> {
        require(params.speed > 0f) { "speed must be positive, was ${params.speed}" }
        val descriptor = checkNotNull(loadedDescriptor) { "$displayName.synthesize called before load()" }
        // SSOT (rule 1): the valid range comes from the loaded descriptor, never a literal here.
        require(params.speed in descriptor.speedRange) {
            "$displayName: speed ${params.speed} is outside the supported range ${descriptor.speedRange}"
        }
        val session = checkNotNull(engine) { "$displayName.synthesize called before load()" }
        val androidVoice =
            (session.voices ?: emptySet()).firstOrNull { it.name == voiceId }
                ?: error("$displayName: voice '$voiceId' is not available")
        return flow {
            for (sentence in TextChunker.intoSentences(text)) {
                emit(synthesizeSentence(session, androidVoice, sentence, params.speed))
            }
            // rule 8: never on the caller's (main) thread - file I/O + the blocking-shaped await
            // both run off it.
        }.flowOn(Dispatchers.IO)
    }

    private suspend fun synthesizeSentence(
        session: TextToSpeech,
        voice: android.speech.tts.Voice,
        sentence: String,
        speed: Float,
    ): FloatArray {
        session.setVoice(voice)
        session.setSpeechRate(speed)
        val file = File.createTempFile("system_tts_", ".wav", appContext.cacheDir)
        return try {
            val done = SystemTtsInit.synthesizeToFile(session, sentence, file)
            if (!done) error("$displayName: synthesis failed or timed out for voice '${voice.name}'")
            // Rule: determine the real sample rate/format of the produced WAV by reading its header
            // rather than assuming - this is exactly what SystemTtsWavReader does per file.
            SystemTtsWavReader.readFloats(file)
        } finally {
            file.delete()
        }
    }

    companion object {
        const val ENGINE_ID = "system_tts"
        private const val MODEL_ID_PREFIX = "system_tts:"

        /** The [ModelDescriptor.modelId] for the installed system-TTS package [enginePackage]. */
        fun modelIdFor(enginePackage: String): String = MODEL_ID_PREFIX + enginePackage

        private fun enginePackageFrom(modelId: String): String {
            require(modelId.startsWith(MODEL_ID_PREFIX)) { "not a system-tts modelId: '$modelId'" }
            return modelId.removePrefix(MODEL_ID_PREFIX)
        }
    }
}
