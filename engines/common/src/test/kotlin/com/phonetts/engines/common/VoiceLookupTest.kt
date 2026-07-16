package com.phonetts.engines.common

import com.phonetts.core.engine.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoiceLookupTest {
    private val voices = listOf(Voice("a", "Alpha", "en"), Voice("b", "Beta", "en"))

    @Test
    fun requireVoiceIndexReturnsThePositionOfAKnownVoice() {
        assertEquals(0, requireVoiceIndex(voices, "a", "X"))
        assertEquals(1, requireVoiceIndex(voices, "b", "X"))
    }

    @Test
    fun requireVoiceIndexFailsClearlyForAnUnknownVoiceId() {
        val error = assertFailsWith<IllegalArgumentException> { requireVoiceIndex(voices, "nope", "X") }
        assertTrue(error.message!!.contains("nope"))
        assertTrue(error.message!!.contains("X"))
    }
}
