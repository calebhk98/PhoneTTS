package com.phonetts.app.text

import android.util.Log

// Thin declaration of the JNI surface implemented in app/src/main/cpp/espeak_jni.cpp. Kept as a
// bare object with no logic of its own - [EspeakPhonemizer] owns every decision (whether native
// loading succeeded, data-path setup, fallback). Package-private-by-convention: only
// [EspeakPhonemizer] should touch this.
internal object EspeakNative {
    private const val LIB_NAME = "phonetts_espeak"
    private const val TAG = "EspeakNative"

    // Attempted once, at class-init time. If the .so is a build-time stub (no espeak-ng source
    // present - see CMakeLists.txt) it still loads fine; [isLibraryLoaded] only tells you the
    // JNI bridge is present, not that espeak-ng itself is functional (nativeInit's return value
    // tells you that).
    val isLibraryLoaded: Boolean =
        runCatching { System.loadLibrary(LIB_NAME) }
            .onFailure { Log.w(TAG, "libphonetts_espeak.so failed to load: ${it.message}") }
            .isSuccess

    /** Returns the sample rate (>= 0) on success, a negative espeak_ERROR code on failure. */
    @JvmStatic
    external fun nativeInit(dataPath: String): Int

    /** Returns an IPA phoneme string, or null if espeak-ng isn't initialized/available. */
    @JvmStatic
    external fun nativeTextToPhonemesIpa(
        text: String,
        voice: String,
    ): String?
}
