package com.phonetts.core.download

import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 hashing + verification of downloaded model files (spec §8, §11.6). A file is only
 * trusted once its hash matches the manifest's expected value; a mismatch is rejected so a
 * corrupt or tampered download is never loaded.
 */
object Sha256Verifier {
    private const val BUFFER_SIZE = 8192
    private const val HEX_RADIX = 16
    private const val BYTE_MASK = 0xFF

    /** Streams [input] through the digest so large weights are never fully buffered in memory. */
    fun hashHex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var read = input.read(buffer)
        while (read >= 0) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
        return digest.digest().toHex()
    }

    fun hashHex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** True if [actualHex] equals [expectedHex], case-insensitively. */
    fun matches(
        actualHex: String,
        expectedHex: String,
    ): Boolean = actualHex.equals(expectedHex, ignoreCase = true)

    fun verify(
        input: InputStream,
        expectedHex: String,
    ): Boolean = matches(hashHex(input), expectedHex)

    fun verify(
        bytes: ByteArray,
        expectedHex: String,
    ): Boolean = matches(hashHex(bytes), expectedHex)

    private fun ByteArray.toHex(): String =
        joinToString("") { byte ->
            val v = byte.toInt() and BYTE_MASK
            v.toString(HEX_RADIX).padStart(2, '0')
        }
}
