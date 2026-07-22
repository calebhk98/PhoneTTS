package com.phonetts.app.runtime

import android.util.Log

// Thin declaration of the JNI surface implemented in app/src/main/cpp/ggmltts/ggmltts_jni.cpp (see
// engines/ggmltts/INTEGRATION.md for the exact CMake wiring the PARENT still needs to add). This is
// the GENERALIZED form of CosyVoiceNative: instead of hardcoding "cosyvoice" into the native call,
// every entry point here takes a [backend] string naming the CrispASR `--backend` id (e.g. "piper",
// "kokoro", "melotts", "cosyvoice", ...) so ONE JNI bridge can serve any CrispASR backend the native
// build links in, with no per-backend Kotlin code. Mirrors CosyVoiceNative: a bare object with no
// decision-making of its own -- NativeGgmlTtsRuntime owns every decision (whether native loading
// succeeded, handles, teardown). Deliberately a SEPARATE object/library from CosyVoiceNative/
// libphonetts_cosyvoice.so -- CosyVoice3 keeps its own proven, already-wired bridge unchanged; this
// is the new, generalized bridge for every OTHER CrispASR backend (issue tracked in
// docs/research/runtime-feasibility-2026-07.md §2's "generalize the JNI past a hardcoded id").
//
// The native lib is built ONLY when the app is assembled with -PwithGgmlTts=true (NDK + CMake + a
// CrispASR source checkout, reusing the SAME fetch script CosyVoice already uses since it's the same
// upstream project -- see INTEGRATION.md); otherwise this object's loadLibrary fails and
// isLibraryLoaded stays false, so NativeGgmlTtsRuntime.isAvailable() reports false and every ggml
// backend routed through this bridge is simply not offered -- the rest of the app assembles and
// runs unchanged (same fail-closed-build policy as CosyVoiceNative/EspeakNative).
internal object GgmlTtsNative {
    private const val LIB_NAME = "phonetts_ggmltts"
    private const val TAG = "GgmlTtsNative"

    /** Attempted once, at class-init time. False if the .so wasn't built (the -PwithGgmlTts off case). */
    val isLibraryLoaded: Boolean =
        runCatching { System.loadLibrary(LIB_NAME) }
            .onFailure { Log.w(TAG, "libphonetts_ggmltts.so failed to load: ${it.message}") }
            .isSuccess

    /**
     * Load [backend]'s ggml stack from [modelDir] (the native side discovers whichever GGUF
     * file(s) that specific CrispASR backend expects as siblings there -- a single voice GGUF for
     * Piper/Kokoro/MeloTTS-style backends, or a multi-stage set like CosyVoice3's). [backend] is the
     * CrispASR `--backend` id (`"piper"`, `"kokoro"`, `"melotts"`, ...) -- the ONE parameter that
     * replaces CosyVoiceNative's hardcoded backend choice. Returns an opaque native handle (>0), or
     * 0 on failure (including an unknown/unlinked [backend] id). [temperature]/[seed] are forwarded
     * as-is for whichever backends use a sampler (see docs/COSYVOICE2.md on why CosyVoice3 needs
     * temperature > 0); a backend that ignores them is free to.
     */
    @JvmStatic
    external fun nativeInit(
        backend: String,
        modelDir: String,
        threads: Int,
        temperature: Float,
        seed: Long,
    ): Long

    /** The output sample rate of the loaded model, or 0 on failure. */
    @JvmStatic
    external fun nativeSampleRate(handle: Long): Int

    /** The baked/discovered voice names the loaded backend reports (the SSOT for the voice list). */
    @JvmStatic
    external fun nativeVoiceNames(handle: Long): Array<String>?

    /**
     * Run the FULL native text→audio pipeline for one utterance on whichever backend [handle] was
     * opened with. Returns finished PCM at [nativeSampleRate], or null on failure.
     */
    @JvmStatic
    external fun nativeSynthesize(
        handle: Long,
        text: String,
        voiceName: String,
    ): FloatArray?

    /** Free the native handle opened by [nativeInit]. */
    @JvmStatic
    external fun nativeFree(handle: Long)
}
