package com.phonetts.app.playback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

private const val TONE_MILLIS = 220
private const val TONE_VOLUME = 70
private const val VIBRATE_MILLIS = 160L
private const val TONE_RELEASE_MILLIS = 400L

/**
 * The end-of-document cue (issue #32): a short beep + haptic buzz fired by [PlaybackService] when a
 * playback flow finishes *naturally* (not on a user Stop). Kept its own class so the service's
 * completion branch stays a one-liner and the Android tone/vibrator plumbing lives in one place.
 *
 * Uses a [ToneGenerator] beep rather than a bundled sound asset (no `SoundPool` sample to ship) and
 * a one-shot [Vibrator] pulse. Both are best-effort: a device with no vibrator, or a tone generator
 * the platform refuses to allocate, simply produces the other half of the cue (or nothing) - a
 * missing cue must never crash playback teardown.
 */
class PlaybackChime(private val context: Context) {
    fun fire() {
        beep()
        vibrate()
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MILLIS)
            // Release after the tone has had time to play; holding it open leaks the generator.
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, TONE_RELEASE_MILLIS)
        }
    }

    private fun vibrate() {
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATE_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
            return
        }
        @Suppress("DEPRECATION")
        vibrator.vibrate(VIBRATE_MILLIS)
    }

    private fun vibrator(): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java) ?: return null
            return manager.defaultVibrator
        }
        @Suppress("DEPRECATION")
        return context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}
