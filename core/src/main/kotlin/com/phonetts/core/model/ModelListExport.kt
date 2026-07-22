package com.phonetts.core.model

import com.phonetts.core.download.hf.HfEndpoints
import com.phonetts.core.registry.ModelUsage
import com.phonetts.core.registry.UnresolvedModelUsage
import kotlin.math.ln
import kotlin.math.pow

/**
 * One identified, downloaded model to include in the "Copy list" export (issue #98). Every field
 * is read straight from the same SSOT the Manage screen already shows — [ModelUsage] for the
 * descriptor/size, [ManageModelFacts] for the derived link/RAM/param/RTF facts (CLAUDE.md rule 1)
 * — never a re-hardcoded model literal.
 */
data class ExportableModel(
    val modelId: String,
    val displayName: String,
    val origin: Origin,
    val sizeBytes: Long,
    /** This model's HF repo id (e.g. "owner/name"), or null if none could be recovered. */
    val hfRepoId: String?,
    /** Peak RAM in bytes — measured if available, else the descriptor's a-priori estimate; null if unknown. */
    val peakRamBytes: Long?,
    /** Estimated parameter count, or null if the on-disk size couldn't yield one. */
    val paramCount: Long?,
    /** Speed as a multiple of real-time, or null if unknown. */
    val realtimeMultiple: Double?,
    /** True if [realtimeMultiple] is a real benchmark measurement rather than a formula prediction. */
    val realtimeIsMeasured: Boolean,
) {
    companion object {
        /**
         * Builds one export row from the exact data the Manage screen already computed for this
         * model — [facts] is optional so a model still exports (name/origin/size only) even mid-
         * refresh, before [ManageModelFacts] has been derived for it.
         */
        fun from(
            usage: ModelUsage,
            facts: ManageModelFacts?,
        ): ExportableModel =
            ExportableModel(
                modelId = usage.descriptor.modelId,
                displayName = usage.descriptor.displayName,
                origin = usage.descriptor.origin,
                sizeBytes = usage.sizeBytes,
                hfRepoId = facts?.hfRepoId,
                peakRamBytes = facts?.peakRamBytes,
                paramCount = facts?.paramCount,
                realtimeMultiple = facts?.realtimeMultiple,
                realtimeIsMeasured = facts?.realtimeIsMeasured ?: false,
            )
    }
}

/**
 * Builds the plain-text "Copy list" export for the Manage-models screen (issue #98): a header with
 * the count and grand total size, one line per identified model (origin, size, HF link, est. RAM,
 * param count, measured/estimated RTF — whichever are known), then one line per downloaded-but-
 * unidentified bundle (issue #8) so the reason it didn't resolve AND, wherever the id allows it, a
 * link to what it probably is are both visible.
 *
 * Pure and deterministic — no Android types, no I/O, no clock — so it is fully unit-testable on a
 * plain JVM (spec §9). Every field is read from what it's handed; a field unknown for a given row
 * is simply OMITTED from that row's line, never shown as a fabricated placeholder (CLAUDE.md rule 1).
 */
object ModelListExport {
    fun build(
        resolved: List<ExportableModel>,
        unresolved: List<UnresolvedModelUsage>,
    ): String {
        val totalBytes = resolved.sumOf { it.sizeBytes } + unresolved.sumOf { it.sizeBytes }
        val count = resolved.size + unresolved.size
        val lines = mutableListOf("Downloaded models ($count) — ${formatBytes(totalBytes)} total")
        resolved.forEach { lines += resolvedLine(it) }
        unresolved.forEach { lines += unresolvedLine(it) }
        return lines.joinToString("\n")
    }

    private fun resolvedLine(model: ExportableModel): String {
        val facts = mutableListOf("${originLabel(model.origin)} · ${formatBytes(model.sizeBytes)}")
        model.peakRamBytes?.let { facts += "Est. RAM ~${formatBytes(it)}" }
        model.paramCount?.let { facts += "~${formatParamCount(it)} params" }
        realtimeFact(model.realtimeMultiple, model.realtimeIsMeasured)?.let { facts += it }
        repoLink(model.hfRepoId, model.modelId)?.let { facts += it }
        return "• ${model.displayName} — ${facts.joinToString(" · ")}"
    }

    private fun unresolvedLine(unresolved: UnresolvedModelUsage): String {
        val facts = mutableListOf("${formatBytes(unresolved.sizeBytes)} · no engine yet")
        repoLink(authoritativeRepoId = null, idToGuessFrom = unresolved.bundleId)?.let { facts += it }
        return "• ${unresolved.bundleId} — ${facts.joinToString(" · ")}"
    }

    private fun realtimeFact(
        multiple: Double?,
        isMeasured: Boolean,
    ): String? {
        if (multiple == null) return null
        val label = if (isMeasured) "measured" else "estimated"
        return "~${formatRealtimeMultiple(multiple)}x real-time ($label)"
    }

    // Prefers the authoritative repo id (curated catalog / Piper index — see InstalledModelFacts)
    // when one is known; otherwise falls back to a best-effort guess from the bundle/model id
    // itself, clearly labeled "(guessed)" so an ambiguous split is never mistaken for a verified
    // link. Returns null (never a broken link) when neither yields anything usable.
    private fun repoLink(
        authoritativeRepoId: String?,
        idToGuessFrom: String,
    ): String? {
        authoritativeRepoId?.let { return HfEndpoints.modelPageUrl(it) }
        val guessed = guessHfRepoId(idToGuessFrom) ?: return null
        return "${HfEndpoints.modelPageUrl(guessed)} (guessed)"
    }

    /**
     * Best-effort "owner/name" recovered from a sanitized bundle/model id (issue #98): downloaded
     * folders are named by sanitizing the HF repo id, which replaces its one '/' with '_'
     * ([com.phonetts.app.ModelStorage.sanitize] in :app). Reversing that is ambiguous whenever
     * either segment itself contains an underscore, so this only ever splits at the FIRST '_' —
     * good enough to be useful, never claimed as certain (callers label it "(guessed)"). Returns
     * null when there's no '_' to split on, or either side would be empty.
     */
    fun guessHfRepoId(id: String): String? {
        val separator = id.indexOf('_')
        if (separator <= 0 || separator == id.length - 1) return null
        return "${id.substring(0, separator)}/${id.substring(separator + 1)}"
    }

    private fun originLabel(origin: Origin): String =
        when (origin) {
            Origin.BUILT_IN -> "Built-in"
            Origin.SIDELOADED -> "Sideloaded"
        }

    /** A compact "82M"/"1.2B" parameter-count label, mirroring the Manage/Browse screens' own. */
    private fun formatParamCount(count: Long): String {
        if (count <= 0L) return "?"
        val millions = count / MILLION
        if (millions >= THOUSAND) return "%.1fB".format(millions / THOUSAND)
        if (millions >= 1.0) return "%.0fM".format(millions)
        val thousands = count / THOUSAND_DIVISOR
        if (thousands >= 1.0) return "%.0fK".format(thousands)
        return count.toString()
    }

    private fun formatRealtimeMultiple(multiple: Double): String = "%.1f".format(multiple)

    /** "1.5 GB" / "320 KB" / "512 B" style formatting, mirroring the Manage screen's own. */
    private fun formatBytes(bytes: Long): String {
        if (bytes < UNIT) return "$bytes B"
        val exponent = (ln(bytes.toDouble()) / ln(UNIT.toDouble())).toInt().coerceIn(1, UNIT_PREFIXES.size)
        val value = bytes / UNIT.toDouble().pow(exponent)
        val rounded = Math.round(value * ROUNDING_FACTOR) / ROUNDING_FACTOR
        return "$rounded ${UNIT_PREFIXES[exponent - 1]}B"
    }

    private const val UNIT = 1024L
    private const val ROUNDING_FACTOR = 10.0
    private val UNIT_PREFIXES = charArrayOf('K', 'M', 'G', 'T')
    private const val THOUSAND = 1000.0
    private const val MILLION = 1_000_000.0
    private const val THOUSAND_DIVISOR = 1_000.0
}
