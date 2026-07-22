package com.phonetts.app.runtime

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Shared async plumbing over `android.speech.tts.TextToSpeech` (issue #77).
 *
 * `TextToSpeech` is callback-based (`OnInitListener`, `UtteranceProgressListener`) and those
 * callbacks are delivered back through this process's main-thread `Looper` regardless of which
 * thread constructed the instance. That rules out blocking any thread with a latch while waiting
 * for one — if the blocked thread IS the main thread, the callback that would unblock it can never
 * run (self-deadlock); if it's some other thread, blocking it is still pointless extra risk for no
 * benefit. Every wait here is a coroutine suspension instead (rule 8: never block for inference —
 * this is the async-native equivalent for a callback API), bounded by [withTimeoutOrNull] so a
 * wedged engine fails closed instead of hanging forever.
 *
 * Used by both [SystemTtsEngine] (the real synthesis session, one per `load()`) and
 * [SystemTtsDiscovery] (the one-shot startup probe that builds each installed engine's
 * [com.phonetts.core.model.ModelDescriptor]) so this tricky part is written exactly once.
 */
object SystemTtsInit {
    private const val INIT_TIMEOUT_MS = 4_000L
    private const val SYNTH_TIMEOUT_MS = 15_000L

    /**
     * Construct + initialize a [TextToSpeech] bound to [enginePackage] (one of the packages
     * [SystemTtsDiscovery.installedEngines] reports), or null if init fails or times out — fail
     * closed rather than handing back a half-initialized instance.
     */
    suspend fun initialize(
        context: Context,
        enginePackage: String,
    ): TextToSpeech? =
        withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var engine: TextToSpeech? = null
                engine =
                    TextToSpeech(
                        context,
                        { status ->
                            val initialized = status == TextToSpeech.SUCCESS
                            if (cont.isActive) cont.resume(if (initialized) engine else null)
                        },
                        enginePackage,
                    )
                cont.invokeOnCancellation { runCatching { engine?.shutdown() } }
            }
        }

    /**
     * Synthesize [text] with [engine]'s CURRENT voice/rate to [file] (a WAV file — Android always
     * writes `synthesizeToFile` output as WAV) and suspend until the engine reports the utterance
     * done, errored, or the wait times out. Returns false on any of those non-success outcomes
     * (fail closed — the caller never treats an incomplete file as usable audio).
     */
    suspend fun synthesizeToFile(
        engine: TextToSpeech,
        text: String,
        file: File,
    ): Boolean {
        val utteranceId = "phonetts_${System.nanoTime()}"
        val completed =
            withTimeoutOrNull(SYNTH_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont -> awaitUtterance(engine, text, file, utteranceId, cont) }
            }
        return completed ?: false
    }

    private fun awaitUtterance(
        engine: TextToSpeech,
        text: String,
        file: File,
        utteranceId: String,
        cont: CancellableContinuation<Boolean>,
    ) {
        engine.setOnUtteranceProgressListener(utteranceListener(utteranceId, cont))
        val queued = engine.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (queued != TextToSpeech.SUCCESS) resumeOnce(cont, false)
    }

    private fun utteranceListener(
        utteranceId: String,
        cont: CancellableContinuation<Boolean>,
    ): UtteranceProgressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId) resumeOnce(cont, true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) resumeOnce(cont, false)
            }

            override fun onError(
                id: String?,
                errorCode: Int,
            ) {
                if (id == utteranceId) resumeOnce(cont, false)
            }
        }

    private fun resumeOnce(
        cont: CancellableContinuation<Boolean>,
        value: Boolean,
    ) {
        if (cont.isActive) cont.resume(value)
    }
}
