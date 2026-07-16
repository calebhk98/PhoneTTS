package com.phonetts.core.audio

import com.phonetts.core.audio.export.WavEncoder
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * Thin façade kept for existing callers: WAV export now lives in the [WavEncoder] child of the
 * shared [com.phonetts.core.audio.export.AudioEncoder] hierarchy (so MP3/Opus/AAC can reuse the
 * same drain + transform machinery). This delegates so `WavWriter().write(...)` keeps working;
 * new code should use [WavEncoder] directly and pass a transform chain if desired.
 */
class WavWriter {
    private val encoder = WavEncoder()

    suspend fun write(
        flow: Flow<FloatArray>,
        sampleRate: Int,
        out: OutputStream,
    ) = encoder.encode(flow, sampleRate, out)
}
