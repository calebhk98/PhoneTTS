package com.phonetts.app.audio.export

import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.phonetts.core.audio.Pcm16
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.audio.export.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

private const val CHANNEL_COUNT = 1
private const val OPUS_BIT_RATE = 64_000

/**
 * Opus audio in a WebM container, via [MediaCodecFileEncoder] (MediaCodec + MediaMuxer).
 *
 * Min-API reality (do not relax without re-checking on a real device): Android's MediaCodec did
 * not ship a usable Opus ENCODER until API 29 (Android 10) — earlier releases only decode Opus.
 * There is also no `.ogg` container support in MediaMuxer at any API level; WebM muxing
 * (`MUXER_OUTPUT_WEBM`) is the only container `MediaMuxer` offers Opus into, and it's only worth
 * using once the encoder itself exists (API 29+). PhoneTTS's minSdk is 24, so this class is
 * unusable on ~API 24-28 devices by construction of the platform, not a bug here: [encodeToFile]
 * and [writeEncoded] both throw [UnsupportedOperationException] below API 29 instead of silently
 * producing a broken/empty file. [com.phonetts.app.audio.export.ExportFormats] only advertises
 * this encoder to the UI when `Build.VERSION.SDK_INT >= 29`, so the failure path here is a
 * defensive backstop for direct callers, not something the picker should ever trigger.
 *
 * NEEDS ON-DEVICE VERIFICATION: unlike AAC, Opus encoders commonly only accept a fixed set of
 * sample rates (8000/12000/16000/24000/48000 Hz). No emulator/device was available in this
 * environment to confirm which of PhoneTTS's engine sample rates the on-device Opus encoder
 * accepts as-is vs. rejects with `IllegalStateException` from `configure()`. If an engine's native
 * rate isn't accepted, that is a real gap (this class does not resample — resampling audio to
 * change speed/pitch is explicitly forbidden by the spec, and silently resampling only for
 * container compatibility was judged out of scope for this ticket) and should be re-evaluated
 * once verified on hardware.
 */
class OpusAudioEncoder(private val tempDir: File) : AudioEncoder() {
    override val format: ExportFormat = FORMAT

    override suspend fun writeEncoded(
        segments: List<FloatArray>,
        sampleRate: Int,
        out: OutputStream,
    ) {
        requireSupported()
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
        requireSupported()
        val pcm = segments.fold(ByteArray(0)) { acc, segment -> acc + Pcm16.encode(segment) }
        ENCODER.encode(pcm, sampleRate, CHANNEL_COUNT, target)
    }

    private fun requireSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException(
                "Opus encoding requires API 29+ (Android's MediaCodec has no Opus encoder below " +
                    "it); this device is API ${Build.VERSION.SDK_INT}. ExportFormats should never " +
                    "offer this encoder on such a device.",
            )
        }
    }

    companion object {
        private const val TEMP_PREFIX = "phonetts_opus_"

        val FORMAT =
            ExportFormat(
                id = "opus",
                displayName = "Opus (.webm)",
                fileExtension = "webm",
                mimeType = "audio/webm",
            )

        private val ENCODER =
            MediaCodecFileEncoder(
                mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS,
                muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
            ) { mediaFormat ->
                mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BIT_RATE)
            }
    }
}
