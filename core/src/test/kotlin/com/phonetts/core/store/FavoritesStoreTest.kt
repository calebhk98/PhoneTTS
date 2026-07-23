package com.phonetts.core.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritesStoreTest {
    @Test
    fun favoriteVoiceIsScopedToBothModelAndVoiceId() {
        val store = FavoritesStore(InMemoryDurableStore())
        store.setFavoriteVoice("piper", "en_US-amy", favorite = true)

        assertTrue(store.isFavoriteVoice("piper", "en_US-amy"))
        assertFalse(store.isFavoriteVoice("piper", "en_US-ryan"), "different voice id is not favorited")
        assertFalse(store.isFavoriteVoice("kokoro", "en_US-amy"), "same voice id in another model is not favorited")
    }

    @Test
    fun toggleVoiceReturnsNewStateAndFlipsBack() {
        val store = FavoritesStore(InMemoryDurableStore())

        assertTrue(store.toggleFavoriteVoice("piper", "amy"), "first toggle favorites it")
        assertFalse(store.toggleFavoriteVoice("piper", "amy"), "second toggle un-favorites it")
        assertTrue(store.favoriteVoices().isEmpty())
    }

    @Test
    fun favoriteVoicesAreNewestFirstAndDeduplicated() {
        val store = FavoritesStore(InMemoryDurableStore())
        store.setFavoriteVoice("piper", "amy", favorite = true)
        store.setFavoriteVoice("kokoro", "sky", favorite = true)
        store.setFavoriteVoice("piper", "amy", favorite = true) // re-add, must not duplicate

        assertEquals(
            listOf(FavoriteVoiceRef("piper", "amy"), FavoriteVoiceRef("kokoro", "sky")),
            store.favoriteVoices(),
        )
    }

    @Test
    fun favoriteModelsToggleAndQuery() {
        val store = FavoritesStore(InMemoryDurableStore())
        assertTrue(store.toggleFavoriteModel("kokoro"))

        assertTrue(store.isFavoriteModel("kokoro"))
        assertEquals(listOf("kokoro"), store.favoriteModels())

        store.setFavoriteModel("kokoro", favorite = false)
        assertFalse(store.isFavoriteModel("kokoro"))
    }

    @Test
    fun flaggingCarriesReasonAndTimestampAndReplacesOnReflag() {
        val store = FavoritesStore(InMemoryDurableStore())
        store.flagModel("mystery", reason = "English slot is actually Russian", atMs = 5L)

        assertTrue(store.isFlagged("mystery"))
        assertEquals(1, store.flaggedModels().size)
        assertEquals("English slot is actually Russian", store.flaggedModels().single().reason)

        store.flagModel("mystery", reason = "still broken", atMs = 9L)
        assertEquals(1, store.flaggedModels().size, "re-flagging replaces, not duplicates")
        assertEquals("still broken", store.flaggedModels().single().reason)
        assertEquals(9L, store.flaggedModels().single().atMs)
    }

    @Test
    fun unflagRemovesTheEntry() {
        val store = FavoritesStore(InMemoryDurableStore())
        store.flagModel("bad")

        store.unflagModel("bad")

        assertFalse(store.isFlagged("bad"))
        assertTrue(store.flaggedModels().isEmpty())
    }

    @Test
    fun everythingReloadsFromAFreshInstanceOverTheSameBacking() {
        val backing = InMemoryDurableStore()
        FavoritesStore(backing).apply {
            setFavoriteVoice("piper", "amy", favorite = true)
            setFavoriteModel("kokoro", favorite = true)
            flagModel("bad", reason = "broken engine", atMs = 3L)
        }

        val reloaded = FavoritesStore(backing)
        assertTrue(reloaded.isFavoriteVoice("piper", "amy"))
        assertTrue(reloaded.isFavoriteModel("kokoro"))
        assertTrue(reloaded.isFlagged("bad"))
        assertEquals("broken engine", reloaded.flaggedModels().single().reason)
    }

    @Test
    fun corruptDocumentLoadsAsEmptyRatherThanThrowing() {
        val backing = InMemoryDurableStore()
        backing.write(FavoritesStore.DOCUMENT_NAME, "garbage {{{")

        val store = FavoritesStore(backing)
        assertTrue(store.favoriteVoices().isEmpty())
        assertTrue(store.favoriteModels().isEmpty())
        assertTrue(store.flaggedModels().isEmpty())
    }
}
