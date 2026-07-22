package com.phonetts.core.download.hf

/**
 * Ways to order a list of [HfModelSummary] search results. Every option reads a field the Hub
 * already returned on the summary, or a value *derived* from one (size/params — see below) — no
 * model list or family is named here (issue #6 SSOT rule: sort/filter options must derive from
 * data, never a hardcoded catalog).
 *
 * [LARGEST_SIZE]/[SMALLEST_SIZE]/[MOST_PARAMS]/[FEWEST_PARAMS] need a per-model [HfSizeEstimate]
 * that the Hub's search response never carries (only the file-tree endpoint does — see
 * [HfCatalog]'s kdoc), so results this app hasn't fetched a size for yet are sorted last regardless
 * of direction (see [HfResultsView.sortBySize]/[sortByParams]) — never given a fabricated 0-byte
 * size just to make them comparable.
 */
enum class HfSortOption {
    /** As the Hub returned them (its own relevance/downloads ranking for the query). */
    RELEVANCE,
    MOST_DOWNLOADS,
    MOST_LIKES,
    NAME_ASC,
    LARGEST_SIZE,
    SMALLEST_SIZE,
    MOST_PARAMS,
    FEWEST_PARAMS,
}

/** True for a sort option whose ordering needs each result's [HfSizeEstimate] — the signal the
 * browse screen uses to eagerly fetch sizes for the whole current result set the moment the user
 * picks one of these (rather than only on request, or leaving most rows stuck "last" forever). */
fun HfSortOption.needsSize(): Boolean = this in SIZE_DEPENDENT_SORTS

private val SIZE_DEPENDENT_SORTS =
    setOf(HfSortOption.LARGEST_SIZE, HfSortOption.SMALLEST_SIZE, HfSortOption.MOST_PARAMS, HfSortOption.FEWEST_PARAMS)

/**
 * A size/parameter-count range to filter results by (issue: sort+filter by size/params). Every
 * bound is optional and independent; `null` on both sides of a pair means "no filter on that
 * dimension". Bytes and params are filtered separately so a user can constrain either, both, or
 * neither. A result whose size isn't known yet (see [HfResultsView.filterBySize]) is EXCLUDED the
 * moment any bound here is set — silently keeping an unmeasured repo in a "small models only" list
 * would defeat the filter's purpose, and this app never presents a guess as a fact.
 */
data class HfSizeParamFilter(
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val minParams: Long? = null,
    val maxParams: Long? = null,
) {
    val isActive: Boolean get() = minBytes != null || maxBytes != null || minParams != null || maxParams != null
}

/**
 * Sorts and filters Hub search results. A filter's *available choices* (see [availableTags]) are
 * always computed from the current result set, not a fixed list — so a tag the Hub starts using
 * tomorrow shows up as a filter option with no code change here (issue #6). Size/param-count
 * sorting and filtering (see [HfSizeParamFilter]) work the same way but need an extra input the tag
 * filter doesn't: a `Map<modelId, HfSizeEstimate>`, since size isn't a field on [HfModelSummary]
 * itself — it's fetched lazily per repo from the file-tree endpoint. Callers pass whatever subset of
 * that map they've fetched so far; this object never fetches anything itself (:core has no network).
 */
object HfResultsView {
    fun sort(
        results: List<HfModelSummary>,
        option: HfSortOption,
        sizeEstimates: Map<String, HfSizeEstimate> = emptyMap(),
    ): List<HfModelSummary> =
        when (option) {
            HfSortOption.RELEVANCE -> results
            HfSortOption.MOST_DOWNLOADS -> results.sortedByDescending { it.downloads }
            HfSortOption.MOST_LIKES -> results.sortedByDescending { it.likes }
            HfSortOption.NAME_ASC -> results.sortedBy { it.id.lowercase() }
            HfSortOption.LARGEST_SIZE -> sortBySize(results, sizeEstimates, descending = true)
            HfSortOption.SMALLEST_SIZE -> sortBySize(results, sizeEstimates, descending = false)
            HfSortOption.MOST_PARAMS -> sortByParams(results, sizeEstimates, descending = true)
            HfSortOption.FEWEST_PARAMS -> sortByParams(results, sizeEstimates, descending = false)
        }

    // Unknown-size results always sort after every known one, in EITHER direction — see the
    // HfSortOption kdoc: an unknown size is not "smallest", it's simply not comparable yet.
    private fun sortBySize(
        results: List<HfModelSummary>,
        sizeEstimates: Map<String, HfSizeEstimate>,
        descending: Boolean,
    ): List<HfModelSummary> {
        val (known, unknown) = results.partition { sizeEstimates[it.id] != null }
        val ascending = known.sortedBy { sizeEstimates.getValue(it.id).knownBytes }
        return (if (descending) ascending.reversed() else ascending) + unknown
    }

    private fun sortByParams(
        results: List<HfModelSummary>,
        sizeEstimates: Map<String, HfSizeEstimate>,
        descending: Boolean,
    ): List<HfModelSummary> {
        val (known, unknown) = results.partition { sizeEstimates[it.id] != null }
        val ascending = known.sortedBy { paramCountOf(it, sizeEstimates) }
        return (if (descending) ascending.reversed() else ascending) + unknown
    }

    // The same estimator the browse UI already uses for its "~82M params" hint (ModelSpeedEstimator)
    // — one formula, applied uniformly, never a per-model lookup (spec rule 1).
    private fun paramCountOf(
        model: HfModelSummary,
        sizeEstimates: Map<String, HfSizeEstimate>,
    ): Long {
        val estimate = sizeEstimates.getValue(model.id)
        return ParameterCountEstimator.estimate(estimate.knownBytes, model.tags)
    }

    /** Every distinct tag present across [results], sorted for a stable menu — the actual choices
     * for a "filter by tag" control, derived from data rather than a hardcoded family/format list. */
    fun availableTags(results: List<HfModelSummary>): List<String> = results.flatMap { it.tags }.distinct().sorted()

    /** Keeps only results carrying [tag]; a blank/null [tag] means "no filter". */
    fun filterByTag(
        results: List<HfModelSummary>,
        tag: String?,
    ): List<HfModelSummary> = if (tag.isNullOrBlank()) results else results.filter { tag in it.tags }

    /** Keeps only results whose known download size falls within [minBytes]..[maxBytes] (either
     * bound null = unbounded on that side); no bound set at all is a no-op, unknown-size results
     * included as-is. Once a bound IS set, an unknown-size result is dropped — see [HfSizeParamFilter]. */
    fun filterBySize(
        results: List<HfModelSummary>,
        sizeEstimates: Map<String, HfSizeEstimate>,
        minBytes: Long?,
        maxBytes: Long?,
    ): List<HfModelSummary> {
        if (minBytes == null && maxBytes == null) return results
        return results.filter { model ->
            val bytes = sizeEstimates[model.id]?.knownBytes ?: return@filter false
            (minBytes == null || bytes >= minBytes) && (maxBytes == null || bytes <= maxBytes)
        }
    }

    /** Same as [filterBySize] but over the estimated parameter count derived from each result's size. */
    fun filterByParamCount(
        results: List<HfModelSummary>,
        sizeEstimates: Map<String, HfSizeEstimate>,
        minParams: Long?,
        maxParams: Long?,
    ): List<HfModelSummary> {
        if (minParams == null && maxParams == null) return results
        return results.filter { model ->
            val estimate = sizeEstimates[model.id] ?: return@filter false
            val params = ParameterCountEstimator.estimate(estimate.knownBytes, model.tags)
            (minParams == null || params >= minParams) && (maxParams == null || params <= maxParams)
        }
    }

    /** Applies tag, size and param filtering, then [option] ordering, in that order. [sizeEstimates]
     * defaults to empty so the pre-existing tag-only call sites (no size/param filter, no size/param
     * sort) keep compiling and behaving exactly as before. */
    fun apply(
        results: List<HfModelSummary>,
        option: HfSortOption,
        tag: String?,
        sizeEstimates: Map<String, HfSizeEstimate> = emptyMap(),
        sizeFilter: HfSizeParamFilter = HfSizeParamFilter(),
    ): List<HfModelSummary> {
        val tagged = filterByTag(results, tag)
        val sized = filterBySize(tagged, sizeEstimates, sizeFilter.minBytes, sizeFilter.maxBytes)
        val paramed = filterByParamCount(sized, sizeEstimates, sizeFilter.minParams, sizeFilter.maxParams)
        return sort(paramed, option, sizeEstimates)
    }
}
