package com.phonetts.core.audio.transform

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

// Sequential scratch spill for FloatArray segments: append during pass one, read back in order
// during pass two. Lets a full-buffer transform (LoudnessNormalize's global-peak pass) run in
// bounded HEAP - the segments live on disk, never all in memory at once. Layout per segment is
// [Int length][length × Float], little/JVM-default via DataOutput, so read-back is byte-lossless.
//
// Android-free by design (java.io only): the scratch [dir] is injected - null means the JVM default
// temp dir, which on Android resolves to app-private cache, still off-heap and app-scoped.
internal class SegmentSpill(dir: File? = null) : Closeable {
    private val file: File = File.createTempFile("phonetts_norm_", ".f32", dir)
    private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))
    private var segmentCount = 0
    private var closed = false

    /** Append one segment to the scratch file (pass one). */
    fun append(segment: FloatArray) {
        out.writeInt(segment.size)
        for (sample in segment) out.writeFloat(sample)
        segmentCount++
    }

    /**
     * Read every spilled segment back in append order, handing one at a time to [consume] so the
     * caller can transform and emit it without ever holding the whole set (pass two).
     */
    fun forEachSegment(consume: (FloatArray) -> Unit) {
        out.flush()
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            repeat(segmentCount) { consume(readSegment(input)) }
        }
    }

    private fun readSegment(input: DataInputStream): FloatArray {
        val size = input.readInt()
        return FloatArray(size) { input.readFloat() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { out.close() }
        file.delete()
    }
}
