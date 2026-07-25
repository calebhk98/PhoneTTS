package com.phonetts.app.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Reproduces (and pins the fix for) the device-confirmed bug: "lock screen / notification shade
 * only shows generation, never playback controls". [PlaybackService] used to be bind-only, so it
 * was destroyed (taking its notification/session with it) the moment the app backgrounded; it is
 * now genuinely STARTED and must reach the foreground promptly (the 5-second contract) with a
 * media session that is actually active and advertises the right transport actions - otherwise the
 * OS has nothing valid to render on the lock screen regardless of how the notification looks.
 *
 * These tests exercise the service directly (Robolectric's [ServiceController]), independent of
 * MainActivity's `repeatOnLifecycle(STARTED)` forwarding bug documented in
 * [PlaybackNotificationFactory] / the accompanying report - that bug lives in a file this module
 * does not own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private lateinit var controller: ServiceController<PlaybackService>

    @Before
    fun setUp() {
        controller = Robolectric.buildService(PlaybackService::class.java).create()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
    }

    @Test
    fun `onStartCommand reaches the foreground with transport actions within the same call`() {
        assertFalse("should start false", PlaybackService.isForegroundActive)

        controller.startCommand(0, 0)

        assertTrue("ensureForeground must mark the service foreground-active", PlaybackService.isForegroundActive)
        val notification = shadowOf(notificationManager).getNotification(PLAYBACK_NOTIFICATION_ID)
        assertNotNull("a real notification must back the foreground contract", notification)
        // prev-paragraph, play/pause, next-paragraph, stop.
        assertEquals(4, notification.actions?.size)
    }

    @Test
    fun `media session is active with PLAY PAUSE STOP and skip actions once playback starts`() {
        controller.startCommand(0, 0)
        val binder = bind()
        binder.attachController(FakePlaybackController())

        binder.onStateChanged(playing = true, paused = false, title = "Chapter 1")

        val session = mediaControllerFrom(currentPlaybackNotification())
        val actions = session.playbackState?.actions ?: 0L
        assertTrue("ACTION_PLAY", actions and PlaybackStateCompat.ACTION_PLAY != 0L)
        assertTrue("ACTION_PAUSE", actions and PlaybackStateCompat.ACTION_PAUSE != 0L)
        assertTrue("ACTION_STOP", actions and PlaybackStateCompat.ACTION_STOP != 0L)
        assertTrue("ACTION_SKIP_TO_NEXT", actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue("ACTION_SKIP_TO_PREVIOUS", actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, session.playbackState?.state)
        assertEquals("Chapter 1", session.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
    }

    @Test
    fun `paused state is reflected on the session`() {
        controller.startCommand(0, 0)
        val binder = bind()
        binder.attachController(FakePlaybackController())

        binder.onStateChanged(playing = true, paused = true, title = null)

        val session = mediaControllerFrom(currentPlaybackNotification())
        assertEquals(PlaybackStateCompat.STATE_PAUSED, session.playbackState?.state)
    }

    @Test
    fun `media session is active as soon as the service is created, before any state change`() {
        // isActive is what the lock screen checks before it will render anything for this session
        // at all - it must not depend on a state change reaching the service first (onCreate already
        // ran in setUp(), no startCommand/onStateChanged has happened yet).
        assertTrue(bind().isMediaSessionActive)
    }

    @Test
    fun `a playing to stopped transition tears the foreground back down`() {
        controller.startCommand(0, 0)
        val binder = bind()
        binder.attachController(FakePlaybackController())
        binder.onStateChanged(playing = true, paused = false, title = "x")
        assertTrue(PlaybackService.isForegroundActive)

        binder.onStateChanged(playing = false, paused = false, title = "x")

        assertFalse(PlaybackService.isForegroundActive)
    }

    @Test
    fun `a media button intent is handled without crashing when no controller is attached`() {
        // MediaButtonReceiver.handleIntent silently no-ops without a session callback target; this
        // guards the AndroidManifest wiring (MEDIA_BUTTON intent-filter + MediaButtonReceiver
        // receiver) actually reaches onStartCommand without throwing.
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        controller.startCommand(0, 0)
        controller.withIntent(intent).startCommand(0, 0)
    }

    private fun bind(): PlaybackService.LocalBinder =
        controller.get().onBind(Intent(context, PlaybackService::class.java)) as PlaybackService.LocalBinder

    private fun currentPlaybackNotification(): Notification =
        shadowOf(notificationManager).getNotification(PLAYBACK_NOTIFICATION_ID)
            ?: run {
                controller.startCommand(0, 0)
                shadowOf(notificationManager).getNotification(PLAYBACK_NOTIFICATION_ID)
                    ?: error("no playback notification posted")
            }

    @Suppress("DEPRECATION")
    private fun mediaControllerFrom(notification: Notification): MediaControllerCompat {
        val token =
            notification.extras.getParcelable<MediaSessionCompat.Token>(Notification.EXTRA_MEDIA_SESSION)
                ?: error("notification is not tied to a MediaSession - lock screen has nothing to render")
        return MediaControllerCompat(context, token)
    }

    private class FakePlaybackController : PlaybackController {
        override val isPlaying: Boolean = true
        override val isPaused: Boolean = false

        override fun pause() = Unit

        override fun resume() = Unit

        override fun stop() = Unit
    }
}
