package com.phonetts.core.download

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Streams [input] into [output], enforcing [DownloadStorageCap] both upfront (against
 * [advertisedLength] when the caller knows one) and while bytes actually arrive - the same guard
 * a network download applies, shared here so every ingestion path (a Hugging Face download AND a
 * sideloaded folder copied via the Storage Access Framework) is held to one rule instead of each
 * re-implementing - and potentially drifting from - its own version of it.
 *
 * A `null`/non-positive [advertisedLength] (an unknown size - some SAF providers don't report one,
 * exactly like an HF repo tree entry that omits a size) skips the upfront check and relies entirely
 * on the streaming one, same as the existing HF download path already does for an unknown-size item.
 *
 * Throws [IOException] naming [fileLabel] the moment the file provably can't fit, without ever
 * buffering the whole file in memory.
 */
object CappedCopy {
    private const val BUFFER_SIZE = 8192

    fun copy(
        input: InputStream,
        output: OutputStream,
        advertisedLength: Long?,
        fileLabel: String,
        freeBytes: () -> Long,
        marginBytes: Long = DownloadStorageCap.DEFAULT_MARGIN_BYTES,
    ) {
        val knownLength = advertisedLength?.takeIf { it > 0L }
        if (DownloadStorageCap.exceedsFreeStorage(knownLength, freeBytes(), marginBytes)) {
            throw IOException(
                "not enough free storage to import $fileLabel (needs $knownLength bytes, ${freeBytes()} free)",
            )
        }
        val cap = DownloadStorageCap.capFor(freeBytes(), marginBytes)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            total += read
            if (total > cap) throw IOException("not enough free storage to finish importing $fileLabel")
            output.write(buffer, 0, read)
            read = input.read(buffer)
        }
    }
}
