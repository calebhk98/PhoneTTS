package com.phonetts.core.prefs

import com.phonetts.core.engine.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoriteVoicesTest {
    private val enVoice = Voice("en-1", "English One", "en")
    private val enVoice2 = Voice("en-2", "English Two", "en")
    private val jaVoice = Voice("ja-1", "Japanese One", "ja")

    @Test
    fun `a voice is not favorite until toggled`() {
        val favorites = FavoriteVoices(InMemoryPreferenceStore())
        assertFalse(favorites.isFavorite(enVoice))
    }

    @Test
    fun `toggling favorites a voice and toggling again unfavorites it`() {
        val favorites = FavoriteVoices(InMemoryPreferenceStore())

        val nowFavorite = favorites.toggleFavorite(enVoice)

        assertTrue(nowFavorite)
        assertTrue(favorites.isFavorite(enVoice))

        val nowNotFavorite = favorites.toggleFavorite(enVoice)

        assertFalse(nowNotFavorite)
        assertFalse(favorites.isFavorite(enVoice))
    }

    @Test
    fun `favorites persist across separate FavoriteVoices instances over the same store`() {
        val store = InMemoryPreferenceStore()
        FavoriteVoices(store).toggleFavorite(enVoice)

        val reloaded = FavoriteVoices(store)

        assertTrue(reloaded.isFavorite(enVoice))
    }

    @Test
    fun `favoritesOf filters a descriptor's voices down to the favorited ones`() {
        val favorites = FavoriteVoices(InMemoryPreferenceStore())
        favorites.toggleFavorite(enVoice2)

        val result = favorites.favoritesOf(listOf(enVoice, enVoice2, jaVoice))

        assertEquals(listOf(enVoice2), result)
    }

    @Test
    fun `default voice is null for a language with none saved`() {
        val favorites = FavoriteVoices(InMemoryPreferenceStore())
        assertNull(favorites.defaultVoiceId("en"))
        assertNull(favorites.defaultVoice("en", listOf(enVoice, enVoice2)))
    }

    @Test
    fun `default voice is saved and read back per language`() {
        val favorites = FavoriteVoices(InMemoryPreferenceStore())

        favorites.setDefaultVoice(enVoice2)
        favorites.setDefaultVoice(jaVoice)

        assertEquals(enVoice2.id, favorites.defaultVoiceId("en"))
        assertEquals(jaVoice.id, favorites.defaultVoiceId("ja"))
        assertEquals(enVoice2, favorites.defaultVoice("en", listOf(enVoice, enVoice2)))
    }

    @Test
    fun `setting a new default voice for a language replaces the previous one`() {
        val favorites = FavoriteVoices(InMemoryPreferenceStore())

        favorites.setDefaultVoice(enVoice)
        favorites.setDefaultVoice(enVoice2)

        assertEquals(enVoice2.id, favorites.defaultVoiceId("en"))
    }

    @Test
    fun `defaultVoice fails closed when the saved id is no longer among the offered voices`() {
        val favorites = FavoriteVoices(InMemoryPreferenceStore())
        favorites.setDefaultVoice(enVoice)

        // The model changed and no longer offers "en-1".
        val result = favorites.defaultVoice("en", listOf(enVoice2))

        assertNull(result)
    }
}
