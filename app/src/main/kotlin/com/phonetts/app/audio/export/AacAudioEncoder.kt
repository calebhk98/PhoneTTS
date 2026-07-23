package com.phonetts.app.audio.export

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.phonetts.core.audio.Pcm16
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.audio.export.ExportFormat
import com.phonetts.core.audio.export.SegmentWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val AAC_BIT_RATE = 128_000
private const val CHANNEL_COUNT = 1

/**
 * AAC-LC audio in an .m4a (MPEG-4) container, via [MediaCodecFileEncoder] (MediaCodec +
 * MediaMuxer). Extends the same [AudioEncoder] base WavEncoder does, so it gets the shared
 * bounded-memory flow-drain + transform-pipeline logic (`AudioEncoder.encode`) for free - this
 * class only owns the AAC-specific byte encoding, per spec's "one generation path, many consumers"
 * rule.
 *
 * Why a temp file: `MediaMuxer` writes to a file path or FD, not an arbitrary [OutputStream] - so
 * the [SegmentWriter] returned by [openWriter] streams PCM to a scratch file (bounded heap: one
 * segment at a time), runs the codec into a temp container on close(), then copies that into [out].
 * Callers who already have a real destination `File` (e.g. a SAF export target opened as a file)
 * should call [encodeToFile] instead - it skips the temp-container/copy detour and encodes straight
 * to the target, still spilling PCM to disk rather than buffering the whole utterance.
 */
class AacAudioEncoder(private val tempDir: File) : AudioEncoder() {
    override val format: ExportFormat = FORMAT

    override fun openWriter(
        sampleRate: Int,
        out: OutputStream,
    ): SegmentWriter =
        MediaCodecSegmentWriter(sampleRate, out, tempDir, ENCODER, format.fileExtension, CHANNEL_COUNT) { }

    /** Encode [flow] straight to [target], spilling PCM to a scratch file first (bounded memory). */
    suspend fun encodeToFile(
        flow: Flow<FloatArray>,
        sampleRate: Int,
        target: File,
    ) = withContext(Dispatchers.IO) {
        val pcm = File.createTempFile(TEMP_PREFIX, ".pcm", tempDir)
        try {
            BufferedOutputStream(FileOutputStream(pcm)).use { sink ->
                flow.collect { segment -> sink.write(Pcm16.encode(segment)) }
            }
            ENCODER.encode(pcm, sampleRate, CHANNEL_COUNT, target)
        } finally {
            pcm.delete()
        }
    }

    companion object {
        private const val TEMP_PREFIX = "phonetts_aac_"

        val FORMAT =
            ExportFormat(
                id = "aac",
                displayName = "AAC (.m4a)",
                fileExtension = "m4a",
                mimeType = "audio/mp4",
            )

        // NOTE (on-device verification needed): MediaCodec's AAC encoder is present on every
        // Android device (it's a mandatory codec), so unlike Opus this has no min-API gate. What
        // has NOT been verified on real hardware here (no device/emulator in this environment) is
        // that every engine's native sample rate is accepted by the AAC encoder's config step -
        // it should be, AAC has no fixed sample-rate table like Opus does, but this needs a real
        // on-device run to confirm end to end (encoder config -> muxer -> playable .m4a).
        private val ENCODER =
            MediaCodecFileEncoder(
                mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
                muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            ) { mediaFormat ->
                mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
    }
}
