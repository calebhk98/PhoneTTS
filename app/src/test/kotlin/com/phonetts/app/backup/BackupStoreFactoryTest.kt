package com.phonetts.app.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.store.FilesDurableStore
import com.phonetts.core.prefs.DocumentPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Confirms [BackupStoreFactory] actually points at the SAME on-disk storage
 * [com.phonetts.app.AppGraph] uses (issue #149) - not a silo of its own that would silently back up
 * an empty/different copy of the user's data. Uses a real Robolectric [android.content.Context]
 * (`filesDir` + `SharedPreferences`), not mocks, so this exercises the real file paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupStoreFactoryTest {
    @Test
    fun `a document saved through one factory-built instance is visible through a second`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        BackupStoreFactory.create(context).library.add("doc1", "Hello world", savedAtMillis = 1L, title = "Greeting")

        val reloaded = BackupStoreFactory.create(context)
        assertEquals("Hello world", reloaded.library.get("doc1")?.text)
    }

    @Test
    fun `the durable store lives under filesDir slash durable, same as AppGraph's`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        BackupStoreFactory.create(context).favoritesStore.setFavoriteModel("kokoro", favorite = true)

        val expectedDir = File(context.filesDir, FilesDurableStore.DIRECTORY_NAME)
        assertTrue("expected ${expectedDir.absolutePath} to exist after a write", expectedDir.exists())
        assertTrue(File(expectedDir, "favorites.json").exists())
    }

    @Test
    fun `preferences-backed stores (document memory, blended voices) also round-trip across instances`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        BackupStoreFactory.create(context).documentMemory.record(
            DocumentPosition("doc1", "piper", "amy", 1.0f, 2),
        )

        val reloaded = BackupStoreFactory.create(context)
        assertEquals("piper", reloaded.documentMemory.resume("doc1")?.engineId)
    }
}
