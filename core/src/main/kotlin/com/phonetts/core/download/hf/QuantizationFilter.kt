package com.phonetts.core.download.hf

/**
 * Groups a repo's file tree by [QuantizationVariant] so a caller can show "this repo offers
 * fp32 / fp16 / q8" and then download only the chosen precision's weight files — plus whatever
 * shared, non-weight files (config.json, tokenizer files, ...) every variant needs regardless of
 * which precision is picked.
 */
object QuantizationFilter {
    /** Distinct precisions present among [files]' weight files (directories are ignored). */
    fun availableVariants(files: List<HfTreeEntry>): Set<QuantizationVariant> =
        files
            .asSequence()
            .filter { it.isFile && QuantizationClassifier.isWeightFile(it.path) }
            .map { QuantizationClassifier.classify(it.path) }
            .toSet()

    /**
     * The precisions [availableVariants] found MINUS [QuantizationVariant.UNKNOWN] — i.e. the
     * precisions a user could meaningfully choose between. A repo whose weight files are all one
     * known precision plus some unlabeled/ambiguous ones (e.g. coqui/XTTS-v2's `mel_stats.pth`,
     * which has a separator but no recognized token) has exactly one *known* variant, not two — see
     * [requiresChoice].
     */
    fun knownVariants(files: List<HfTreeEntry>): Set<QuantizationVariant> =
        availableVariants(files) - QuantizationVariant.UNKNOWN

    /**
     * True only when a repo genuinely offers more than one identifiable precision (issue #9: many
     * repos ship a single precision plus a few ambiguously-named auxiliary weight files — e.g. a
     * vocoder or stats file with no fp16/q8/etc. token in its name — and those must NOT force a
     * prompt the user has no real choice to make). Callers should skip the picker and download the
     * full, unfiltered file list whenever this is false.
     */
    fun requiresChoice(files: List<HfTreeEntry>): Boolean = knownVariants(files).size > 1

    /**
     * The files needed to reconstruct the model folder for one [variant]: every weight file
     * labeled [variant], plus every non-weight file (shared across all variants — e.g. config,
     * tokenizer, vocab) AND every weight file whose precision is [QuantizationVariant.UNKNOWN].
     * An unlabeled weight file can't be proven to belong to a *different* precision than the one
     * requested, so it is always kept rather than silently dropped from every variant's download
     * (issue #9 — this is what previously made picking a variant lose files like `mel_stats.pth`).
     * Directories and weight files of a different, *known* precision are excluded.
     */
    fun filesForVariant(
        files: List<HfTreeEntry>,
        variant: QuantizationVariant,
    ): List<HfTreeEntry> = files.filter { entry -> entry.isFile && isNeededFor(entry, variant) }

    private fun isNeededFor(
        entry: HfTreeEntry,
        variant: QuantizationVariant,
    ): Boolean {
        if (!QuantizationClassifier.isWeightFile(entry.path)) return true
        val classified = QuantizationClassifier.classify(entry.path)
        if (classified == QuantizationVariant.UNKNOWN) return true
        return classified == variant
    }
}

/**
 * Builds a download plan (spec: reuse [HfDownloadPlan]'s shape, don't invent a second one) scoped
 * to a single [QuantizationVariant], so a budget device fetches only the precision it needs
 * instead of every variant the repo ships.
 */
object HfQuantizedDownloadPlan {
    fun forVariant(
        modelId: String,
        files: List<HfTreeEntry>,
        variant: QuantizationVariant,
        revision: String = HfCatalog.DEFAULT_REVISION,
    ): List<HfDownloadItem> {
        val scopedFiles = QuantizationFilter.filesForVariant(files, variant)
        return HfDownloadPlan.forFiles(modelId, scopedFiles, revision)
    }
}
