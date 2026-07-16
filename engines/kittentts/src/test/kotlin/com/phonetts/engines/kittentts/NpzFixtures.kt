package com.phonetts.engines.kittentts

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hand-builds minimal `.npy`/`.npz` byte arrays for [NpyArrayTest], [KittenVoiceTableTest], and
 * [KittenEngineSynthesizeTest] -- there is no numpy dependency in this module (or a real
 * `voices.npz` fixture file) to read from, so tests construct the exact byte layout
 * [NpyArray]/[KittenVoiceTable] are expected to decode.
 */
object NpzFixtures {
    private val MAGIC =
        byteArrayOf(
            0x93.toByte(),
            'N'.code.toByte(),
            'U'.code.toByte(),
            'M'.code.toByte(),
            'P'.code.toByte(),
            'Y'.code.toByte(),
        )
    private const val ALIGNMENT = 64
    private const val PREFIX_SIZE = 10 // 6-byte magic + 2-byte version + 2-byte header-len field

    /** Builds a v1.0 `.npy` payload for a `(1, [floats.size])` float32 row, matching real exports. */
    fun npyBytes(floats: FloatArray): ByteArray {
        val header = "{'descr': '<f4', 'fortran_order': False, 'shape': (1, ${floats.size}), }"
        val unpaddedTotal = PREFIX_SIZE + header.length + 1 // +1 for the trailing '\n'
        val paddedTotal = ((unpaddedTotal + ALIGNMENT - 1) / ALIGNMENT) * ALIGNMENT
        val padding = " ".repeat(paddedTotal - unpaddedTotal)
        val headerBytes = (header + padding + "\n").toByteArray(Charsets.US_ASCII)

        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(byteArrayOf(1, 0)) // version 1.0
        out.write(byteArrayOf((headerBytes.size and 0xFF).toByte(), ((headerBytes.size shr 8) and 0xFF).toByte()))
        out.write(headerBytes)
        val floatBuffer = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { floatBuffer.putFloat(it) }
        out.write(floatBuffer.array())
        return out.toByteArray()
    }

    /** Zips [voices] (name -> 256-dim row) into a `voices.npz`-shaped archive, entries named `<name>.npy`. */
    fun npzBytes(voices: Map<String, FloatArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, floats) in voices) {
                zip.putNextEntry(ZipEntry("$name.npy"))
                zip.write(npyBytes(floats))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
