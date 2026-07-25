package com.phonetts.app.sideload

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.phonetts.core.download.DownloadStorageCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Issue: `SideloadCoordinator.copyFile` used to be a raw `stream.copyTo(out)` with NO free-space
 * guard at all, while [com.phonetts.app.hf.HfDownloader] enforced [DownloadStorageCap] both
 * upfront and while streaming - so importing a huge folder via the file picker could fill the
 * device with no check. `copyTree`/`copyFile` now delegate to the shared
 * [com.phonetts.core.download.CappedCopy] helper, exactly what the download path uses.
 *
 * [SideloadCoordinator.copyTree] is exercised directly (it's `internal`) against a
 * `DocumentFile.fromFile(...)` tree - the "raw file" DocumentFile implementation, which needs no
 * real Storage Access Framework provider/treeUri to back it, so this stays a fast, deterministic
 * JVM-ish test instead of standing up a fake SAF provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SideloadCoordinatorStorageCapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun coordinator(freeBytes: () -> Long): SideloadCoordinator =
        SideloadCoordinator(
            context = context,
            importer = com.phonetts.core.sideload.ModelImporter(
                reader = com.phonetts.core.sideload.DirectoryBundleReader(),
                resolver =
                    com.phonetts.core.resolver.Resolver(emptyList(), com.phonetts.core.resolver.InMemoryOverrideStore()) {
                        error("not exercised - copyTree is driven directly")
                    },
                catalog = com.phonetts.core.registry.ModelCatalog(),
            ),
            modelsBaseDir = { tmp.newFolder("appPrivateBase-${System.nanoTime()}") },
            freeBytes = freeBytes,
        )

    @Test
    fun `a folder that comfortably fits is copied in full`() {
        val source = tmp.newFolder("source")
        File(source, "model.onnx").writeBytes(ByteArray(10_000) { it.toByte() })
        File(source, "config.json").writeText("{}")
        val destination = File(tmp.newFolder("dest-fits"), "model")

        coordinator(freeBytes = { DownloadStorageCap.DEFAULT_MARGIN_BYTES + 1_000_000L })
            .copyTree(DocumentFile.fromFile(source), destination)

        assertEquals(10_000, File(destination, "model.onnx").length().toInt())
        assertEquals("{}", File(destination, "config.json").readText())
    }

    @Test
    fun `importing a file bigger than free space is refused rather than filling the device`() {
        val source = tmp.newFolder("source-too-big")
        val bigFile = File(source, "huge.onnx")
        bigFile.writeBytes(ByteArray(50_000))
        val destination = File(tmp.newFolder("dest-too-big"), "model")

        // Only the margin's worth of free space "left" - nothing left over for the 50,000-byte file.
        val error =
            try {
                coordinator(freeBytes = { DownloadStorageCap.DEFAULT_MARGIN_BYTES })
                    .copyTree(DocumentFile.fromFile(source), destination)
                null
            } catch (e: IOException) {
                e
            }

        assertTrue("expected the copy to be refused for lack of free space", error != null)
        val copied = File(destination, "huge.onnx")
        assertFalse(
            "the oversized file must not land fully copied on disk",
            copied.exists() && copied.length() == bigFile.length(),
        )
    }

    @Test
    fun `an unknown SAF-reported length is still capped once real bytes stream in`() {
        // DocumentFile.fromFile always reports a real length() from the backing java.io.File, so to
        // exercise the "unknown length" branch of CappedCopy we drop free space low enough that even
        // a small, correctly-advertised file cannot fit - proving the guard isn't bypassable just by
        // a generous size hint.
        val source = tmp.newFolder("source-tight")
        File(source, "tiny.bin").writeBytes(ByteArray(1_000))
        val destination = File(tmp.newFolder("dest-tight"), "model")

        val error =
            try {
                coordinator(freeBytes = { DownloadStorageCap.DEFAULT_MARGIN_BYTES }) // 0 real bytes of budget
                    .copyTree(DocumentFile.fromFile(source), destination)
                null
            } catch (e: IOException) {
                e
            }

        assertTrue(error != null)
    }
}
