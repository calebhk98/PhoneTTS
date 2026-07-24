package com.phonetts.core.prefs

import com.phonetts.core.engine.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultVoicePreferenceTest {
    private val enVoice = Voice("en-1", "English One", "en")
    private val enVoice2 = Voice("en-2", "English Two", "en")
    private val jaVoice = Voice("ja-1", "Japanese One", "ja")

    @Test
    fun `default voice is null for a language with none saved`() {
        val prefs = DefaultVoicePreference(InMemoryPreferenceStore())
        assertNull(prefs.defaultVoiceId("en"))
        assertNull(prefs.defaultVoice("en", listOf(enVoice, enVoice2)))
    }

    @Test
    fun `default voice is saved and read back per language`() {
        val prefs = DefaultVoicePreference(InMemoryPreferenceStore())

        prefs.setDefaultVoice(enVoice2)
        prefs.setDefaultVoice(jaVoice)

        assertEquals(enVoice2.id, prefs.defaultVoiceId("en"))
        assertEquals(jaVoice.id, prefs.defaultVoiceId("ja"))
        assertEquals(enVoice2, prefs.defaultVoice("en", listOf(enVoice, enVoice2)))
    }

    @Test
    fun `setting a new default voice for a language replaces the previous one`() {
        val prefs = DefaultVoicePreference(InMemoryPreferenceStore())

        prefs.setDefaultVoice(enVoice)
        prefs.setDefaultVoice(enVoice2)

        assertEquals(enVoice2.id, prefs.defaultVoiceId("en"))
    }

    @Test
    fun `defaultVoice fails closed when the saved id is no longer among the offered voices`() {
        val prefs = DefaultVoicePreference(InMemoryPreferenceStore())
        prefs.setDefaultVoice(enVoice)

        // The model changed and no longer offers "en-1".
        val result = prefs.defaultVoice("en", listOf(enVoice2))

        assertNull(result)
    }

    @Test
    fun `legacy favorites are readable then cleared so migration runs only once`() {
        val store = InMemoryPreferenceStore()
        // Simulate the old model-blind favorites set written by the previous FavoriteVoices class.
        store.putStringSet("favorite_voice_ids", setOf("en-1", "ja-1"))
        val prefs = DefaultVoicePreference(store)

        assertEquals(setOf("en-1", "ja-1"), prefs.legacyFavoriteVoiceIds())

        prefs.clearLegacyFavorites()

        assertTrue(prefs.legacyFavoriteVoiceIds().isEmpty(), "cleared legacy set must read empty next time")
    }
}
