package com.phonetts.app.audio

import com.phonetts.core.audio.buffer.ChunkSpill
import com.phonetts.core.audio.buffer.GeneratedAudio
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the "leaked spill file" audit item (long-document mode's [ChunkSpill] scratch file):
 * [com.phonetts.app.ui.TtsViewModel] (owned by another in-flight change, not edited here) creates a
 * [GeneratedAudio] and immediately calls its private `beginSession` - which retains the new buffer
 * in a `session` field and closes the PREVIOUS session's buffer (`if (session !== next)
 * session?.audio?.close()`) - BEFORE generation starts, per its own kdoc:
 * "Adopt the session BEFORE generation starts, not after it succeeds... publishing it up front
 * means whatever lands in it stays usable no matter how the run ends."
 *
 * This test reproduces that exact adoption pattern directly against the [GeneratedAudio]/
 * [ChunkSpill] primitives (Android-free, so it needs no ViewModel/Robolectric), standing in for
 * `beginSession`, and asserts the invariant that pattern depends on: replacing a held session's
 * buffer with a new one closes the OLD one's scratch file (so an interrupted run never leaks it),
 * while an in-flight buffer that has NOT yet been superseded is never touched. If a future edit to
 * TtsViewModel ever went back to adopting only on SUCCESS (the old, leaky behavior this guards
 * against), this test would keep passing since it tests the adoption primitive itself - so it is
 * documented, not silently assumed, that this is the primitive's contract, and TtsViewModel's own
 * call sites (`generateAudio()`/`startPlaybackFrom()`, both calling `beginSession` synchronously
 * right after constructing the buffer, before launching `genJob`) are what actually need to keep
 * following it.
 */
class GeneratedAudioSessionAdoptionTest {
    private class SessionHolder {
        var audio: GeneratedAudio? = null
            private set

        /** Mirrors TtsViewModel.beginSession: adopt [next], closing the previous one if superseded. */
        fun adopt(next: GeneratedAudio) {
            if (audio !== next) audio?.close()
            audio = next
        }
    }

    @Test
    fun `adopting a new session closes the previous one's spill file`() {
        val holder = SessionHolder()
        val spillA = File.createTempFile("phonetts_spill_test_a_", ".pcm")
        val spillB = File.createTempFile("phonetts_spill_test_b_", ".pcm")

        val audioA = GeneratedAudio(ChunkSpill(spillA, maxLiveSamples = 4))
        holder.adopt(audioA) // e.g. generateAudio() #1: create -> beginSession, before genJob runs
        audioA.append(FloatArray(8) { it.toFloat() }) // generation produced SOME audio before being interrupted
        assertTrue("session A's spill file should exist while it is the live session", spillA.exists())

        // A second generation starts (e.g. the user hits Play/Generate again) before the first
        // finished - the exact "interrupted run" scenario the audit is about.
        val audioB = GeneratedAudio(ChunkSpill(spillB, maxLiveSamples = 4))
        holder.adopt(audioB)

        assertFalse("superseded session A's scratch file must be released, not leaked", spillA.exists())
        assertTrue("the newly adopted session B must be untouched", spillB.exists())

        holder.adopt(audioB) // re-adopting the SAME session (e.g. a no-op re-publish) must not close it
        assertTrue("re-adopting the still-current session must not close its own spill file", spillB.exists())

        holder.audio?.close() // mirrors onCleared()
        assertFalse(spillB.exists())
    }
}
