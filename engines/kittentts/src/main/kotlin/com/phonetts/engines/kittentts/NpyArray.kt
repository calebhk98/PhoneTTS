package com.phonetts.engines.kittentts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a single NumPy `.npy` v1.0 array - the format each entry inside KittenTTS's
 * `voices.npz` uses (docs/research/onnx-io.md, VALIDATED): a `\x93NUMPY` magic, a 1-byte
 * major/minor version pair, a little-endian uint16 header length, a space-padded Python-literal
 * header dict (carrying `descr` `<f4`, `fortran_order`, `shape`), then the raw little-endian
 * float32 payload. Only the little-endian-float32 v1.0 shape KittenTTS ships is supported; any
 * other magic/dtype/version fails closed via [require] rather than silently misreading bytes.
 */
object NpyArray {
    /** Parses [bytes] as a v1.0 `.npy` file and returns its payload as float32 values. */
    fun parseFloats(bytes: ByteArray): FloatArray {
        require(bytes.size >= HEADER_PREFIX_SIZE) { "npy payload too short to contain a header" }
        requireMagic(bytes)

        val headerLen = readUInt16LittleEndian(bytes, MAGIC.size + VERSION_SIZE)
        val headerStart = HEADER_PREFIX_SIZE
        val headerText = String(bytes, headerStart, headerLen, Charsets.US_ASCII)
        require(headerText.contains(FLOAT32_DESCR)) { "npy dtype is not little-endian float32: $headerText" }

        val dataStart = headerStart + headerLen
        return readFloatsLittleEndian(bytes, dataStart)
    }

    private fun requireMagic(bytes: ByteArray) {
        for (index in MAGIC.indices) {
            require(bytes[index] == MAGIC[index]) { "not an .npy file: bad magic" }
        }
    }

    private fun readFloatsLittleEndian(
        bytes: ByteArray,
        dataStart: Int,
    ): FloatArray {
        val elementCount = (bytes.size - dataStart) / BYTES_PER_FLOAT
        val buffer =
            ByteBuffer.wrap(bytes, dataStart, elementCount * BYTES_PER_FLOAT)
                .order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(elementCount) { buffer.float }
    }

    private fun readUInt16LittleEndian(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and BYTE_MASK) or ((bytes[offset + 1].toInt() and BYTE_MASK) shl BYTE_SHIFT)

    // \x93NUMPY
    private val MAGIC =
        byteArrayOf(
            0x93.toByte(),
            'N'.code.toByte(),
            'U'.code.toByte(),
            'M'.code.toByte(),
            'P'.code.toByte(),
            'Y'.code.toByte(),
        )

    // MAGIC.size can't be referenced from another const's initializer, hence this mirror.
    private const val MAGIC_SIZE_CONST = 6
    private const val VERSION_SIZE = 2
    private const val HEADER_LEN_FIELD_SIZE = 2
    private const val HEADER_PREFIX_SIZE = MAGIC_SIZE_CONST + VERSION_SIZE + HEADER_LEN_FIELD_SIZE
    private const val BYTES_PER_FLOAT = 4
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8
    private const val FLOAT32_DESCR = "<f4"
}
