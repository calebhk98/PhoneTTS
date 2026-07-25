package com.phonetts.core.download

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [CappedCopy] is the one free-space guard shared by every ingestion path (a Hugging Face download
 * AND a sideloaded folder copied via the Storage Access Framework - see
 * `com.phonetts.app.sideload.SideloadCoordinator`) so a huge file/folder can never silently fill
 * the device, no matter which path brought it in.
 */
class CappedCopyTest {
    private val margin = DownloadStorageCap.DEFAULT_MARGIN_BYTES

    @Test
    fun `copies every byte when the file comfortably fits`() {
        val bytes = ByteArray(10_000) { it.toByte() }
        val output = ByteArrayOutputStream()

        CappedCopy.copy(
            input = bytes.inputStream(),
            output = output,
            advertisedLength = bytes.size.toLong(),
            fileLabel = "model.onnx",
            freeBytes = { margin + 1_000_000L },
        )

        assertTrue(bytes.contentEquals(output.toByteArray()))
    }

    @Test
    fun `an advertised length that cannot fit is rejected before any byte moves`() {
        val bytes = ByteArray(10_000)
        val output = ByteArrayOutputStream()

        // advertisedLength is bigger than the (freeBytes - margin) budget below, and freeBytes
        // leaves 0 real bytes of budget once the margin is reserved.
        val error =
            assertFailsWith<IOException> {
                CappedCopy.copy(
                    input = bytes.inputStream(),
                    output = output,
                    advertisedLength = margin + 1_000L,
                    fileLabel = "huge.gguf",
                    freeBytes = { margin },
                )
            }

        assertTrue(error.message.orEmpty().contains("huge.gguf"), error.message.toString())
        assertEquals(0, output.size(), "no bytes should have been written once the upfront check rejected it")
    }

    @Test
    fun `an unknown advertised length is not pre-rejected but is still capped as bytes stream in`() {
        val bytes = ByteArray(10_000)
        val output = ByteArrayOutputStream()

        // advertisedLength null means an unknown size (e.g. a SAF provider that reports none), and
        // freeBytes leaves only 100 real bytes of budget.
        val error =
            assertFailsWith<IOException> {
                CappedCopy.copy(
                    input = bytes.inputStream(),
                    output = output,
                    advertisedLength = null,
                    fileLabel = "mystery.bin",
                    freeBytes = { margin + 100L },
                )
            }

        assertTrue(error.message.orEmpty().contains("mystery.bin"), error.message.toString())
        assertTrue(output.size() <= 100, "should stop at (or just past) the cap, not copy the whole 10,000 bytes")
    }

    @Test
    fun `a zero or negative advertised length is treated the same as unknown`() {
        val bytes = ByteArray(10)
        val output = ByteArrayOutputStream()

        CappedCopy.copy(
            input = bytes.inputStream(),
            output = output,
            advertisedLength = 0L,
            fileLabel = "zero-length.json",
            freeBytes = { margin + 1_000L },
        )

        assertEquals(10, output.size())
    }
}
