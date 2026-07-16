package com.phonetts.app.audio.export

import android.content.Context
import android.os.Build
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.audio.export.WavEncoder

/**
 * SSOT for which export-format consumers exist. The export-format picker (UI) is meant to
 * enumerate [available] and read `id`/`displayName`/`fileExtension`/`mimeType` off each
 * encoder's `format` — nothing about formats should be hardcoded in the UI layer, same discipline
 * as model facts (spec's SSOT rule). [WavEncoder] is core's reference/always-available encoder;
 * AAC and Opus live here in `:app` because MediaCodec is Android-only (see CLAUDE.md module
 * layout — `:core` has no Android dependency).
 *
 * Opus is included only when `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` (API 29): that is
 * the first OS version whose MediaCodec ships an Opus ENCODER at all (see [OpusAudioEncoder]'s
 * kdoc for the full reasoning). Offering it below API 29 would list a picker option that throws on
 * use, so it is omitted there rather than shipped broken — this mirrors the spec's "fail closed,
 * never guess" discipline (rule 4) applied to format availability instead of model identification.
 */
object ExportFormats {
    /**
     * All export-format consumers usable on this device right now. [context] supplies a
     * private, writable directory ([Context.getCacheDir]) the MediaCodec-backed encoders use for
     * their temp-file detour (see [AacAudioEncoder] / [OpusAudioEncoder] kdoc for why they need one).
     */
    fun available(context: Context): List<AudioEncoder> {
        val encoders = mutableListOf<AudioEncoder>(WavEncoder(), AacAudioEncoder(context.cacheDir))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            encoders += OpusAudioEncoder(context.cacheDir)
        }
        return encoders
    }
}
