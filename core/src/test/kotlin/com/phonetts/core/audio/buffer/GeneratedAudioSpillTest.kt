package com.phonetts.core.audio.buffer

import com.phonetts.core.audio.RecordingSink
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RATE = 24_000

// Seam tests for GeneratedAudio's opt-in long-document spill mode (issue #34). The guarantee under
// test: spilling older chunks to disk is LOSSLESS - replay/read-back from index 0 returns exactly
// what was generated, byte-for-byte, whether a chunk is served from RAM or from the scratch file.
class GeneratedAudioSpillTest {
    @Test
    fun spilledChunksReadBackByteIdenticalFromIndexZero() {
        val chunks = (0 until 8).map { i -> FloatArray(4) { (i * 10 + it) + 0.5f } }
        val spillFile = File.createTempFile("phonetts_spill_test_", ".bin")
        val audio = GeneratedAudio(ChunkSpill(spillFile, maxLiveSamples = 5))

        chunks.forEach(audio::append)
        audio.markComplete()

        assertEquals(chunks.size, audio.count.value)
        assertTrue(spillFile.length() > 0, "older chunks should have been evicted to the scratch file")
        // Replay from 0: every index - including the ones now living only on disk - is byte-identical.
        chunks.indices.forEach { index -> assertContentEquals(chunks[index], audio.chunkAt(index)) }
        audio.close()
        assertTrue(!spillFile.exists(), "close() should delete the scratch file")
    }

    @Test
    fun snapshotMatchesTheNoSpillBufferExactly() {
        val chunks = (0 until 10).map { i -> FloatArray(3) { (i - it).toFloat() * 0.25f } }
        val spillFile = File.createTempFile("phonetts_spill_test_", ".bin")
        val spilled = GeneratedAudio(ChunkSpill(spillFile, maxLiveSamples = 4))
        val heapOnly = GeneratedAudio()

        chunks.forEach { spilled.append(it) }
        chunks.forEach { heapOnly.append(it) }

        assertEquals(
            heapOnly.snapshot().flatMap { it.toList() },
            spilled.snapshot().flatMap { it.toList() },
        )
        spilled.close()
    }

    @Test
    fun playbackDrainsASpilledBufferInOrderWithoutRegeneration() =
        runTest {
            val chunks = listOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f), floatArrayOf(0.5f))
            val spillFile = File.createTempFile("phonetts_spill_test_", ".bin")
            val audio = GeneratedAudio(ChunkSpill(spillFile, maxLiveSamples = 3))
            chunks.forEach(audio::append)
            audio.markComplete()
            val sink = RecordingSink()

            BufferedPlayback().play(audio, RATE, sink)

            assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f), sink.recorded.toList())
            audio.close()
        }

    @Test
    fun replayFromZeroTwiceIsStableAcrossTheSpillBoundary() {
        val chunks = (0 until 6).map { i -> FloatArray(4) { (i * 4 + it).toFloat() } }
        val spillFile = File.createTempFile("phonetts_spill_test_", ".bin")
        val audio = GeneratedAudio(ChunkSpill(spillFile, maxLiveSamples = 4))
        chunks.forEach(audio::append)

        val firstPass = (0 until audio.count.value).map { audio.chunkAt(it).toList() }
        val secondPass = (0 until audio.count.value).map { audio.chunkAt(it).toList() }

        assertEquals(firstPass, secondPass)
        assertEquals(chunks.map { it.toList() }, firstPass)
        audio.close()
    }
}
