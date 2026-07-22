package com.phonetts.app.runtime

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.phonetts.core.engine.Voice as CoreVoice
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.model.Origin
import com.phonetts.core.model.ResourceCost
import java.io.File

/**
 * Discovers the on-device Android `TextToSpeech` engine(s) — Google, Samsung, or whatever else is
 * installed — and builds one [ModelDescriptor] per engine (issue #77): an "always available" model
 * that needs no download, because it's already on the phone. Runs once at startup, kicked off
 * (non-blockingly — see its kdoc) by [com.phonetts.app.AppGraph.hydrate].
 *
 * Every fact on the resulting descriptor — voices, language, sample rate — is read from the real
 * `TextToSpeech` API, never hardcoded (SSOT, CLAUDE.md rule 1); an engine whose facts can't be
 * established with confidence (init fails, no offline-playable voice, no readable sample rate) is
 * skipped rather than guessed at (rule 4's fail-closed spirit).
 *
 * MANIFEST NOTE — not applied by this change, no manifest file is touched here: on Android 11+
 * (API 30) seeing another app's installed services via `PackageManager` needs a `<queries>`
 * declaration in `AndroidManifest.xml`:
 * ```xml
 * <queries>
 *     <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
 * </queries>
 * ```
 * Without it, [installedEngines] silently sees none of Google's/Samsung's TTS services on API 30+
 * (this app's own targetSdk is 35) — a fail-closed "no system voices offered" outcome, not a crash,
 * but the feature is inert until that `<queries>` block is added.
 */
object SystemTtsDiscovery {
    /** One installed TTS engine's package name and the user-visible label Android reports for it. */
    data class InstalledEngine(val packageName: String, val label: String)

    /** Every TTS engine service currently installed on the device, deduplicated by package name. */
    fun installedEngines(context: Context): List<InstalledEngine> {
        val packageManager = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return packageManager.queryIntentServices(intent, 0)
            .mapNotNull { resolveInfo ->
                val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
                InstalledEngine(serviceInfo.packageName, resolveInfo.loadLabel(packageManager).toString())
            }
            .distinctBy { it.packageName }
    }

    /**
     * Probes every installed engine and builds its descriptor. Best-effort per engine: one that
     * fails to initialize, exposes no voice usable fully offline, or won't report a sample rate is
     * skipped rather than the whole discovery failing (one broken vendor TTS shouldn't hide Google's).
     */
    suspend fun discover(
        context: Context,
        engineId: String,
    ): List<ModelDescriptor> = installedEngines(context).mapNotNull { probeDescriptor(context, engineId, it) }

    private suspend fun probeDescriptor(
        context: Context,
        engineId: String,
        installed: InstalledEngine,
    ): ModelDescriptor? {
        val session = SystemTtsInit.initialize(context, installed.packageName) ?: return null
        return try {
            buildDescriptorOrNull(context, engineId, installed, session)
        } finally {
            runCatching { session.shutdown() }
        }
    }

    private suspend fun buildDescriptorOrNull(
        context: Context,
        engineId: String,
        installed: InstalledEngine,
        session: TextToSpeech,
    ): ModelDescriptor? {
        val voices = offlineVoices(session)
        if (voices.isEmpty()) return null
        val defaultVoice = defaultVoiceAmong(session, voices)
        val sampleRate = probeSampleRate(context, session) ?: return null
        return ModelDescriptor(
            modelId = SystemTtsEngine.modelIdFor(installed.packageName),
            engineId = engineId,
            displayName = "System — ${installed.label}",
            // BUILT_IN: it ships with the OS/vendor, never downloaded through this app (rule 7 is
            // moot here — there are no weights of ours to bundle or fetch).
            origin = Origin.BUILT_IN,
            sampleRate = sampleRate,
            voices = voices,
            defaultVoiceId = defaultVoice.id,
            // setSpeechRate is the model's genuine native rate knob (rule 2) — Android's public API
            // documents no hard bounds, so this is a conservative practical range, not a literal
            // duplicated elsewhere (the UI reads it from here, same as every other engine).
            parameters = listOf(ModelParameter.speed(SPEED_RANGE, DEFAULT_SPEED)),
            // Synthesis runs out-of-process in the OS's own TTS service; this app has no basis for a
            // peak-RAM estimate of its own footprint, so honest-unknown rather than a guess.
            resourceCost = ResourceCost.UNKNOWN,
        )
    }

    // Only voices playable right now with no extra download and no network round-trip: this app is
    // fully-offline by design (CLAUDE.md), so a voice that needs connectivity or an unfetched voice
    // pack would silently fail (or silently phone home) if offered here.
    //
    // `TextToSpeech.getVoices()` returns a Set — unordered by contract — so sorted by name at the
    // end for a stable dropdown order across launches, not because any model fact depends on it.
    private fun offlineVoices(session: TextToSpeech): List<CoreVoice> =
        (session.voices ?: emptySet())
            .asSequence()
            .filterNot { it.isNetworkConnectionRequired }
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .map(::toCoreVoice)
            .distinctBy { it.id }
            .sortedBy { it.name }
            .toList()

    private fun toCoreVoice(voice: android.speech.tts.Voice): CoreVoice =
        CoreVoice(
            id = voice.name,
            name = "${voice.locale.displayName} (${voice.name})",
            language = voice.locale.toLanguageTag(),
        )

    private fun defaultVoiceAmong(
        session: TextToSpeech,
        voices: List<CoreVoice>,
    ): CoreVoice {
        val preferred = session.voice ?: session.defaultVoice
        return voices.firstOrNull { it.id == preferred?.name } ?: voices.first()
    }

    // Rule: never assume a sample rate — synthesize a short probe utterance and read the WAV header
    // Android actually wrote, the same way every real per-sentence synthesis call will.
    private suspend fun probeSampleRate(
        context: Context,
        session: TextToSpeech,
    ): Int? {
        val probeFile = File.createTempFile("system_tts_probe_", ".wav", context.cacheDir)
        return try {
            val ok = SystemTtsInit.synthesizeToFile(session, PROBE_TEXT, probeFile)
            if (!ok) return null
            SystemTtsWavReader.readHeader(probeFile)?.sampleRate?.takeIf { it > 0 }
        } finally {
            probeFile.delete()
        }
    }

    private const val PROBE_TEXT = "1"
    private val SPEED_RANGE = 0.25f..4.0f
    private const val DEFAULT_SPEED = 1.0f
}
