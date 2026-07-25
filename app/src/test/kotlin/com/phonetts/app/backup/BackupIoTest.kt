package com.phonetts.app.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.phonetts.core.backup.BackupDecodeResult
import com.phonetts.core.backup.RestoreMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end coverage of [BackupIo] (issue #149) - the `:app` glue between SAF file I/O and the
 * `:core` export/decode/restore seam - using real file-backed `Uri`s (Robolectric's ContentResolver
 * handles `file://` through the real, non-shadowed framework path) rather than mocking the SAF
 * contract away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupIoTest {
    @Test
    fun `export then import round-trips a document into a second, empty context's stores`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            BackupStoreFactory.create(context).library.add("doc1", "Hello backup", savedAtMillis = 1L, title = "Greeting")
            val file = File.createTempFile("phonetts-backup", ".json")
            val uri = Uri.fromFile(file)

            val exportMessage = BackupIo.exportTo(context, uri)
            assertTrue(exportMessage.contains("Exported"))
            assertTrue(file.readText().contains("Hello backup"))

            when (val decoded = BackupIo.readAndDecode(context, uri)) {
                is BackupDecodeResult.Success -> {
                    val restoreMessage = BackupIo.applyImport(context, decoded.document, RestoreMode.MERGE)
                    assertTrue(restoreMessage.contains("Restored"))
                }
                else -> throw AssertionError("expected a successful decode, got $decoded")
            }

            assertEquals("Hello backup", BackupStoreFactory.create(context).library.get("doc1")?.text)
            file.delete()
        }

    @Test
    fun `reading a garbage file is reported as corrupt, never thrown`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val file = File.createTempFile("phonetts-backup-garbage", ".json")
            file.writeText("not a backup file {{{")
            val uri = Uri.fromFile(file)

            val result = BackupIo.readAndDecode(context, uri)

            assertTrue(result is BackupDecodeResult.Corrupt)
            file.delete()
        }

    @Test
    fun `exporting with an empty library still succeeds and states weights were not included`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val file = File.createTempFile("phonetts-backup-empty", ".json")
            val uri = Uri.fromFile(file)

            val message = BackupIo.exportTo(context, uri)

            assertTrue(message.contains("not included", ignoreCase = true))
            file.delete()
        }
}
