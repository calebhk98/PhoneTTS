package com.phonetts.core.format

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * THE way a byte count is rendered for a human, everywhere in the app.
 *
 * There were six separate implementations of this, and they disagreed: a 500 KB file read as
 * "500 KB" on the Manage screen and "0.0 MB" on Browse and in the update banner, while the
 * benchmark screens showed bare integer MB with no GB tier at all. Same fact, different answer
 * depending on which screen you happened to be looking at - the exact duplication rule 1 forbids.
 *
 * Lives in `:core` (pure Kotlin, no Android) so `:app` screens and `:core`'s own model-list export
 * share one definition rather than mirroring each other, which is how they drifted in the first
 * place.
 */
object ByteFormat {
    /** "512 B" / "320.5 KB" / "85.4 MB" / "1.5 GB" - one decimal above the byte tier. */
    fun format(bytes: Long): String {
        if (bytes < UNIT) return "$bytes B"
        val exponent = (ln(bytes.toDouble()) / ln(UNIT.toDouble())).toInt().coerceIn(1, UNIT_PREFIXES.size)
        val value = bytes / UNIT.toDouble().pow(exponent)
        val rounded = (value * ROUNDING_FACTOR).roundToLong() / ROUNDING_FACTOR
        return "$rounded ${UNIT_PREFIXES[exponent - 1]}B"
    }

    /** [format] for a value that may be unknown; [unknown] is shown verbatim when it is. */
    fun formatOrUnknown(
        bytes: Long?,
        unknown: String = "unknown",
    ): String = bytes?.let(::format) ?: unknown

    private const val UNIT = 1024L
    private const val ROUNDING_FACTOR = 10.0
    private val UNIT_PREFIXES = charArrayOf('K', 'M', 'G', 'T')
}

/**
 * THE single definition of a compact parameter-count label ("82M" / "1.2B"), everywhere in the app.
 * Was defined separately in `ModelListExport` and (again) in two screens - same drift rule 1 forbids
 * as [ByteFormat]. Any code that needs to show a model's parameter count reads it from here.
 */
object CountFormat {
    /** "82M" / "1.2B" / "500K" / a bare digit string for anything smaller; "?" for a non-positive count. */
    fun formatParamCount(count: Long): String {
        if (count <= 0L) return "?"
        val millions = count / MILLION
        if (millions >= THOUSAND) return "%.1fB".format(millions / THOUSAND)
        if (millions >= 1.0) return "%.0fM".format(millions)
        val thousands = count / THOUSAND_DIVISOR
        if (thousands >= 1.0) return "%.0fK".format(thousands)
        return count.toString()
    }

    private const val THOUSAND = 1000.0
    private const val MILLION = 1_000_000.0
    private const val THOUSAND_DIVISOR = 1_000.0
}
