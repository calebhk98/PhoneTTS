package com.phonetts.app.audio.export

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.phonetts.core.audio.Pcm16
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.audio.export.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

private const val AAC_BIT_RATE = 128_000
private const val CHANNEL_COUNT = 1

/**
 * AAC-LC audio in an .m4a (MPEG-4) container, via [MediaCodecFileEncoder] (MediaCodec +
 * MediaMuxer). Extends the same [AudioEncoder] base WavEncoder does, so it gets the shared
 * flow-drain + transform-chain logic (`AudioEncoder.encode`) for free — this class only owns the
 * AAC-specific byte encoding, per spec's "one generation path, many consumers" rule.
 *
 * Why a temp file: `MediaMuxer` writes to a file path or FD, not an arbitrary [OutputStream] — so
 * it cannot satisfy the core `writeEncoded(segments, sampleRate, out: OutputStream)` contract
 * directly the way WavEncoder (which just streams bytes) can. Rather than changing that contract
 * (which would break WavEncoder and every other consumer expecting an OutputStream), [writeEncoded]
 * buffers the encoded container to a temp file under [tempDir] and copies it into [out] afterwards.
 * That keeps `AudioEncoder.encode(flow, sampleRate, out, transforms)` working unchanged for AAC too.
 * Callers who already have a real destination `File` (e.g. a SAF export target opened as a file)
 * should call [encodeToFile] directly instead — it is an ADDITIVE path alongside the base contract,
 * not a replacement for it, and it skips the temp-file/copy detour entirely.
 */
class AacAudioEncoder(private val tempDir: File) : AudioEncoder() {
    override val format: ExportFormat = FORMAT

    override suspend fun writeEncoded(
        segments: List<FloatArray>,
        sampleRate: Int,
        out: OutputStream,
    ) {
        val temp = File.createTempFile(TEMP_PREFIX, ".${format.fileExtension}", tempDir)
        try {
            encodeToFile(segments, sampleRate, temp)
            temp.inputStream().use { it.copyTo(out) }
        } finally {
            temp.delete()
        }
    }

    /** Encode straight to [target], skipping the temp-file/copy [writeEncoded] needs for an OutputStream. */
    suspend fun encodeToFile(
        segments: List<FloatArray>,
        sampleRate: Int,
        target: File,
    ) = withContext(Dispatchers.IO) {
        val pcm = segments.fold(ByteArray(0)) { acc, segment -> acc + Pcm16.encode(segment) }
        ENCODER.encode(pcm, sampleRate, CHANNEL_COUNT, target)
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
        // that every engine's native sample rate is accepted by the AAC encoder's config step —
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
