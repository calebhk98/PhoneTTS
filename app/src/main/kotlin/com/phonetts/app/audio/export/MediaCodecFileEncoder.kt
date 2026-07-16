package com.phonetts.app.audio.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

// Shared MediaCodec dequeue/drain plumbing used by both AacAudioEncoder and OpusAudioEncoder.
// This is plain composition, NOT part of the core AudioEncoder hierarchy — that hierarchy already
// eliminates the flow-drain/transform duplication (AudioEncoder.encode is inherited by every
// child, WavEncoder included). This class exists one level below that: it stops the two
// MediaCodec-backed children from each re-implementing the same encoder-loop boilerplate.
private const val TIMEOUT_US = 10_000L

/** Encodes raw PCM16 bytes into [target] using a MediaCodec encoder muxed via [muxerOutputFormat]. */
internal class MediaCodecFileEncoder(
    private val mimeType: String,
    private val muxerOutputFormat: Int,
    private val configure: (MediaFormat) -> Unit,
) {
    fun encode(
        pcm: ByteArray,
        sampleRate: Int,
        channelCount: Int,
        target: File,
    ) {
        val mediaFormat = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)
        configure(mediaFormat)
        val codec = MediaCodec.createEncoderByType(mimeType)
        codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(target.absolutePath, muxerOutputFormat)
        try {
            drive(codec, muxer, pcm)
        } finally {
            releaseQuietly(codec, muxer)
        }
    }

    private fun drive(
        codec: MediaCodec,
        muxer: MediaMuxer,
        pcm: ByteArray,
    ) {
        val session = CodecSession(codec, muxer)
        var offset = 0
        var eosSent = false
        while (!session.outputDone) {
            if (!eosSent) {
                val next = session.feedInput(pcm, offset)
                eosSent = next >= pcm.size
                offset = next
            }
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

/** Mutable per-encode state: how much of [pcm] has been queued and whether the muxer has started. */
private class CodecSession(
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
) {
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    var outputDone = false
        private set

    /** Queue as much of [pcm] starting at [offset] as the next free input buffer holds. */
    fun feedInput(
        pcm: ByteArray,
        offset: Int,
    ): Int {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return offset
        val remaining = pcm.size - offset
        if (remaining <= 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return pcm.size
        }
        val buffer = codec.getInputBuffer(index) ?: return offset
        val chunk = minOf(buffer.capacity(), remaining)
        buffer.clear()
        buffer.put(pcm, offset, chunk)
        codec.queueInputBuffer(index, 0, chunk, 0, 0)
        return offset + chunk
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
