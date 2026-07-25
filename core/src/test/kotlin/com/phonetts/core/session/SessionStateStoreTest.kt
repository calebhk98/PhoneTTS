package com.phonetts.core.session

import com.phonetts.core.store.DurableStore
import com.phonetts.core.store.InMemoryDurableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The persistence seam behind "nothing I did is lost when the app restarts". A relaunch is modelled
 * exactly as it happens on device: a BRAND NEW store instance constructed over the same backing
 * document, since process death takes every in-memory field with it.
 */
class SessionStateStoreTest {
    private val backing = InMemoryDurableStore()

    private fun relaunch() = SessionStateStore(backing)

    @Test
    fun readerTextSurvivesARelaunch() {
        SessionStateStore(backing).updateReader { it.copy(text = "the quick brown fox") }

        assertEquals("the quick brown fox", relaunch().reader().text)
    }

    @Test
    fun everyAudioToggleSurvivesARelaunch() {
        // All eight used to reset on every launch - the whole point of this store.
        SessionStateStore(backing).updateReader {
            it.copy(
                trimSilence = true,
                normalizeVolume = true,
                crossfadeJoins = true,
                bassCut = true,
                presenceBoost = true,
                deEss = true,
                tempoBoost = true,
                tempoFactor = 1.75f,
            )
        }

        val restored = relaunch().reader()
        assertTrue(restored.trimSilence)
        assertTrue(restored.normalizeVolume)
        assertTrue(restored.crossfadeJoins)
        assertTrue(restored.bassCut)
        assertTrue(restored.presenceBoost)
        assertTrue(restored.deEss)
        assertTrue(restored.tempoBoost)
        assertEquals(1.75f, restored.tempoFactor)
    }

    @Test
    fun listSortAndFiltersSurviveARelaunchPerScreen() {
        val store = SessionStateStore(backing)
        store.updateBrowse { it.copy(sortKey = "downloads", ascending = false, filters = mapOf("language" to "en")) }
        store.updateManage { it.copy(sortKey = "size", query = "kokoro") }
        store.updateBenchmark { it.copy(sortKey = "rtf", ascending = true) }

        val restored = relaunch()
        assertEquals("downloads", restored.browse().sortKey)
        assertFalse(restored.browse().ascending)
        assertEquals("en", restored.browse().filters["language"])
        assertEquals("size", restored.manage().sortKey)
        assertEquals("kokoro", restored.manage().query)
        assertEquals("rtf", restored.benchmark().sortKey)
        // Each screen's choices are independent - one screen's sort must not leak into another's.
        assertEquals(null, restored.browse().query.takeIf { it.isNotEmpty() })
    }

    @Test
    fun aCorruptDocumentLoadsAsDefaultsRatherThanThrowing() {
        backing.write(SessionStateStore.DOCUMENT_NAME, "{ this is not json")

        val restored = relaunch()

        assertEquals("", restored.reader().text)
        assertFalse(restored.reader().trimSilence)
    }

    @Test
    fun anUnreadableStoreLoadsAsDefaults() {
        val throwing =
            object : DurableStore {
                override fun read(name: String): String? = null

                override fun write(
                    name: String,
                    contents: String,
                ) = throw java.io.IOException("disk full")

                override fun delete(name: String) = Unit
            }

        val store = SessionStateStore(throwing)
        // A write that fails must not crash the app - persistence is best-effort, like every other
        // store in :core.
        store.updateReader { it.copy(text = "still fine") }

        assertEquals("still fine", store.reader().text)
    }

    @Test
    fun clearForgetsEverything() {
        val store = SessionStateStore(backing)
        store.updateReader { it.copy(text = "something") }

        store.clear()

        assertEquals("", relaunch().reader().text)
    }
}
