package com.phonetts.app.audio.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.phonetts.core.audio.Pcm16
import com.phonetts.core.audio.export.SegmentWriter
import kotlinx.coroutines.CancellationException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

// Shared MediaCodec dequeue/drain plumbing used by both AacAudioEncoder and OpusAudioEncoder.
// This is plain composition, NOT part of the core AudioEncoder hierarchy - that hierarchy already
// eliminates the flow-drain/transform duplication (AudioEncoder.encode is inherited by every
// child, WavEncoder included). This class exists one level below that: it stops the two
// MediaCodec-backed children from each re-implementing the same encoder-loop boilerplate.
//
// Bounded memory (issue #33): the PCM is read from a scratch FILE as a stream, fed one input
// buffer at a time, so a book-length export never materializes its raw PCM as one giant byte[].
private const val TIMEOUT_US = 10_000L

/**
 * Runs [step] repeatedly until [isDone] reports true, checking [isCancelled] before every
 * iteration and throwing [CancellationException] the instant it does (issue: export cannot be
 * cancelled) - the exact checkpoint [MediaCodecFileEncoder.drive] needs around its MediaCodec
 * dequeue/drain loop, extracted so the checkpoint's own correctness can be unit-tested on a plain
 * JVM without a real (or Robolectric-shadowed) MediaCodec/MediaMuxer pair.
 */
internal fun runUntilDoneOrCancelled(
    isDone: () -> Boolean,
    isCancelled: () -> Boolean,
    step: () -> Unit,
) {
    while (!isDone()) {
        if (isCancelled()) throw CancellationException("MediaCodec export cancelled")
        step()
    }
}

/** Encodes raw PCM16 bytes (streamed from [pcmSource]) into [target] muxed via [muxerOutputFormat]. */
internal class MediaCodecFileEncoder(
    private val mimeType: String,
    private val muxerOutputFormat: Int,
    private val configure: (MediaFormat) -> Unit,
) {
    // [isCancelled] defaults to "never" so every existing call site (in particular
    // [MediaCodecSegmentWriter.close], a plain, non-suspend [SegmentWriter.close] override that
    // cannot itself observe a coroutine's cancellation) keeps compiling unchanged; callers that DO
    // have a live coroutine context (see [com.phonetts.core.audio.export.AudioEncoder.encode],
    // which threads its own cancellation state all the way down to here) get a real checkpoint.
    fun encode(
        pcmSource: File,
        sampleRate: Int,
        channelCount: Int,
        target: File,
        isCancelled: () -> Boolean = { false },
    ) {
        val mediaFormat = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)
        configure(mediaFormat)
        val codec = MediaCodec.createEncoderByType(mimeType)
        codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(target.absolutePath, muxerOutputFormat)
        try {
            pcmSource.inputStream().buffered().use { input -> drive(codec, muxer, input, isCancelled) }
        } finally {
            releaseQuietly(codec, muxer)
        }
    }

    // Bounded memory (issue #33) already streams PCM instead of buffering it; this adds the missing
    // cancellation checkpoint (issue: export cannot be cancelled) - the same per-item guard
    // [com.phonetts.app.hf.HfDownloader.copyCapped] already applies per buffer, just applied per
    // codec dequeue/drain cycle here so a long AAC/Opus pass can actually be interrupted mid-export
    // instead of running to completion regardless of the caller's coroutine being cancelled. The
    // checkpoint itself is [runUntilDoneOrCancelled] - pulled out on its own so it is unit-testable
    // without a real (or Robolectric-shadowed) MediaCodec/MediaMuxer.
    private fun drive(
        codec: MediaCodec,
        muxer: MediaMuxer,
        input: InputStream,
        isCancelled: () -> Boolean,
    ) {
        val session = CodecSession(codec, muxer)
        var eosSent = false
        runUntilDoneOrCancelled(isDone = { session.outputDone }, isCancelled = isCancelled) {
            if (!eosSent) eosSent = session.feedInput(input)
            session.drainOutput()
        }
    }

    private fun releaseQuietly(
        codec: MediaCodec,
        muxer: MediaMuxer,
    ) {
        runCatching { codec.stop() }
        codec.release()
        runCatching { muxer.stop() }
        muxer.release()
    }
}

/** Mutable per-encode state: streams PCM into the codec and whether the muxer has started. */
private class CodecSession(
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
) {
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    var outputDone = false
        private set

    /** Queue the next slice of PCM from [input]; returns true once end-of-stream has been signalled. */
    fun feedInput(input: InputStream): Boolean {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: return false
        buffer.clear()
        val scratch = ByteArray(buffer.capacity())
        val read = input.read(scratch)
        if (read <= 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        buffer.put(scratch, 0, read)
        codec.queueInputBuffer(index, 0, read, 0, 0)
        return false
    }

    /** Pull one available output event (format-changed or an encoded buffer) and handle it. */
    fun drainOutput() {
        val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
        when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer()
            index >= 0 -> writeSample(index)
        }
    }

    private fun startMuxer() {
        trackIndex = muxer.addTrack(codec.outputFormat)
        muxer.start()
        muxerStarted = true
    }

    private fun writeSample(index: Int) {
        val encoded = codec.getOutputBuffer(index)
        if (encoded != null && bufferInfo.size > 0 && muxerStarted) {
            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
        }
        val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        codec.releaseOutputBuffer(index, false)
        if (isEos) outputDone = true
    }
}

// Shared bounded-memory [SegmentWriter] for the MediaCodec-backed formats. Spills PCM16 to a scratch
// file as segments arrive (heap stays at one segment), then on close() runs the codec from that
// file into a temp container and copies it into [out]. [precondition] runs up front so an
// unsupported codec (Opus below API 29) fails before any scratch file is created.
internal class MediaCodecSegmentWriter(
    private val sampleRate: Int,
    private val out: OutputStream,
    private val tempDir: File,
    private val encoder: MediaCodecFileEncoder,
    private val extension: String,
    private val channelCount: Int,
    precondition: () -> Unit,
    // Threaded down from the suspend [com.phonetts.core.audio.export.AudioEncoder.encode] that
    // opened this writer (issue: export cannot be cancelled) - `close()` below is a plain,
    // non-suspend [SegmentWriter] override, so it has no way to observe the caller's coroutine
    // cancellation on its own; this closure is its only route to it. Defaults to "never cancelled"
    // so a direct construction that doesn't care (or can't supply one) still compiles.
    private val isCancelled: () -> Boolean = { false },
) : SegmentWriter {
    private val pcmScratch: File

    init {
        precondition()
        pcmScratch = File.createTempFile("phonetts_pcm_", ".pcm", tempDir)
    }

    private val pcmOut = BufferedOutputStream(FileOutputStream(pcmScratch))

    override fun write(segment: FloatArray) {
        pcmOut.write(Pcm16.encode(segment))
    }

    override fun close() {
        pcmOut.flush()
        pcmOut.close()
        val container = File.createTempFile("phonetts_enc_", ".$extension", tempDir)
        try {
            encoder.encode(pcmScratch, sampleRate, channelCount, container, isCancelled)
            container.inputStream().use { it.copyTo(out) }
        } finally {
            pcmScratch.delete()
            container.delete()
        }
    }
}
