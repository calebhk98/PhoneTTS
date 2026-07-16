package com.phonetts.app.playback

import android.content.Context
import android.media.AudioManager
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat

/**
 * Thin wrapper around [AudioManagerCompat]'s focus APIs for [PlaybackService]: [request] asks for
 * `AUDIOFOCUS_GAIN` tagged as media/speech, and [onFocusLost] (wired to the attached
 * [PlaybackController.pause]) fires if the OS takes focus away — e.g. an incoming call or another
 * media app starting. Using the compat request/listener types keeps this correct across API
 * levels without a manual `Build.VERSION.SDK_INT` branch (minSdk 24 predates `AudioFocusRequest`).
 */
class PlaybackAudioFocus(
    context: Context,
    private val onFocusLost: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var activeRequest: AudioFocusRequestCompat? = null

    private val listener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                onFocusLost()
            }
        }

    /** Request focus if not already held. No-op if it is already held or there is no manager. */
    fun request() {
        val manager = audioManager ?: return
        if (activeRequest != null) return
        val built = buildRequest()
        if (AudioManagerCompat.requestAudioFocus(manager, built) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            activeRequest = built
        }
    }

    /** Give focus back up. No-op if nothing is currently held. */
    fun abandon() {
        val manager = audioManager ?: return
        val request = activeRequest ?: return
        AudioManagerCompat.abandonAudioFocusRequest(manager, request)
        activeRequest = null
    }

    private fun buildRequest(): AudioFocusRequestCompat =
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
}
