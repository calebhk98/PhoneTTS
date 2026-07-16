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
     * The files needed to reconstruct the model folder for one [variant]: every weight file
     * labeled [variant], plus every non-weight file (shared across all variants — e.g. config,
     * tokenizer, vocab). Directories and weight files of a *different* precision are excluded.
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
        return QuantizationClassifier.classify(entry.path) == variant
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
