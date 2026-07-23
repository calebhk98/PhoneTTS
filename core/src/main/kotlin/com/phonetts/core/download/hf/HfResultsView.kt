package com.phonetts.core.download.hf

/**
 * Ways to order a list of [HfModelSummary] search results. Every option reads a field the Hub
 * already returned on the summary, or a value *derived* from one (size/params/RTF - see below) - no
 * model list or family is named here (issue #6 SSOT rule: sort/filter options must derive from
 * data, never a hardcoded catalog).
 *
 * [LARGEST_SIZE]/[SMALLEST_SIZE]/[MOST_PARAMS]/[FEWEST_PARAMS] need a per-model [HfSizeEstimate]
 * that the Hub's search response never carries (only the file-tree endpoint does - see
 * [HfCatalog]'s kdoc), so results this app hasn't fetched a size for yet are sorted last regardless
 * of direction (see [HfResultsView.sortBySize]/[sortByParams]) - never given a fabricated 0-byte
 * size just to make them comparable.
 *
 * [FASTEST_RTF]/[SLOWEST_RTF] need a measured real-time factor, which only exists once the SAME
 * repo has actually been downloaded and benchmarked on this device (see
 * [com.phonetts.core.metrics.BenchmarkHistory]) - the Hub has no notion of "how fast does this run
 * on your phone". A result with no benchmark on record sorts last in either direction, same as an
 * unknown size (see [HfResultsView.sortByRtf]) - never a guessed/interpolated number.
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
    FASTEST_RTF,
    SLOWEST_RTF,
}

/** True for a sort option whose ordering needs each result's [HfSizeEstimate] - the signal the
 * browse screen uses to eagerly fetch sizes for the whole current result set the moment the user
 * picks one of these (rather than only on request, or leaving most rows stuck "last" forever). */
fun HfSortOption.needsSize(): Boolean = this in SIZE_DEPENDENT_SORTS

/** True for a sort option whose ordering needs each result's measured real-time factor (see
 * [HfSortOption]'s kdoc). Unlike size, there is nothing to eagerly fetch for this - a repo's RTF is
 * either already on disk (benchmarked after a previous download) or it isn't, so this exists mainly
 * so callers can label/explain the option consistently with [needsSize] rather than for any fetch
 * trigger of its own. */
fun HfSortOption.needsRtf(): Boolean = this in RTF_DEPENDENT_SORTS

private val SIZE_DEPENDENT_SORTS =
    setOf(HfSortOption.LARGEST_SIZE, HfSortOption.SMALLEST_SIZE, HfSortOption.MOST_PARAMS, HfSortOption.FEWEST_PARAMS)

private val RTF_DEPENDENT_SORTS = setOf(HfSortOption.FASTEST_RTF, HfSortOption.SLOWEST_RTF)

/**
 * Whether the browse view model should eagerly fetch every current result's [HfSizeEstimate] right
 * now (issue: max-size filter regression). Sizes are needed not only when [sort] orders by them
 * ([HfSortOption.needsSize]) but also whenever [sizeFilter] has an active bound - [filterBySize]
 * excludes a result the moment its size isn't known, so without this second trigger those rows would
 * simply vanish (their own per-row fetch never runs, because a filtered-out row is never composed to
 * begin with) rather than eventually appearing once their size resolves.
 */
fun needsEagerSizeFetch(
    sort: HfSortOption,
    sizeFilter: HfSizeParamFilter,
): Boolean = sort.needsSize() || sizeFilter.isActive

/**
 * Show all results, only ones already downloaded on this device, or only ones not yet downloaded
 * (issue: installed/not-installed filter). Mirrors [HfSortOption]/[HfSizeParamFilter] in deriving
 * from data the app already has (the local model catalog), never a hardcoded model list.
 */
enum class HfInstalledFilter {
    ALL,
    INSTALLED_ONLY,
    NOT_INSTALLED_ONLY,
}

/**
 * The optional filter/sort inputs [HfResultsView.apply] needs beyond the tag - size (#7), language,
 * and RTF (issue: RTF sort/filter) - bundled into one parameter, mirroring the UI layer's own
 * RowActions/SizeState pattern, so [HfResultsView.apply] stays under detekt's parameter-count limit
 * as more lazily-known per-model facts get added over time. Every field defaults to "no
 * filter"/"nothing known" so a caller that only cares about tag filtering keeps compiling unchanged.
 */
data class HfResultFilters(
    val sizeEstimates: Map<String, HfSizeEstimate> = emptyMap(),
    val sizeFilter: HfSizeParamFilter = HfSizeParamFilter(),
    val language: String? = null,
    val rtfEstimates: Map<String, Double> = emptyMap(),
    // Advanced filters (issue #107) - every one defaults to "no filter" so existing tag-only callers
    // keep compiling and behaving identically. [compatibility]/[formats] are fetched per repo (same
    // file-tree fetch the size estimate uses); [engineLabels] is derived from the summary alone (see
    // HfEngineClassifier); [minRealtimeMultiple] is the RTF slider's size-derived estimate bound.
    val compatibility: Map<String, RunCompatibility> = emptyMap(),
    val supportedFilter: HfSupportedFilter = HfSupportedFilter.ALL,
    val formats: Map<String, Set<HfFileFormat>> = emptyMap(),
    val formatFilter: HfFileFormat? = null,
    val minRealtimeMultiple: Double? = null,
    val engineLabels: Map<String, String> = emptyMap(),
    val engineFilter: String? = null,
)

/**
 * A size/parameter-count range to filter results by (issue: sort+filter by size/params). Every
 * bound is optional and independent; `null` on both sides of a pair means "no filter on that
 * dimension". Bytes and params are filtered separately so a user can constrain either, both, or
 * neither. A result whose size isn't known yet (see [HfResultsView.filterBySize]) is EXCLUDED the
 * moment any bound here is set - silently keeping an unmeasured repo in a "small models only" list
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
 * always computed from the current result set, not a fixed list - so a tag the Hub starts using
 * tomorrow shows up as a filter option with no code change here (issue #6). Size/param-count
 * sorting and filtering (see [HfSizeParamFilter]) work the same way but need an extra input the tag
 * filter doesn't: a `Map<modelId, HfSizeEstimate>`, since size isn't a field on [HfModelSummary]
 * itself - it's fetched lazily per repo from the file-tree endpoint. Callers pass whatever subset of
 * that map they've fetched so far; this object never fetches anything itself (:core has no network).
 */
object HfResultsView {
    fun sort(
        results: List<HfModelSummary>,
        option: HfSortOption,
        sizeEstimates: Map<String, HfSizeEstimate> = emptyMap(),
        rtfEstimates: Map<String, Double> = emptyMap(),
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
            // Fastest first = lowest RTF first (a smaller ratio is faster); slowest first = highest.
            HfSortOption.FASTEST_RTF -> sortByRtf(results, rtfEstimates, descending = false)
            HfSortOption.SLOWEST_RTF -> sortByRtf(results, rtfEstimates, descending = true)
        }

    // Unknown-size results always sort after every known one, in EITHER direction - see the
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

    // Same unknown-last rule as sortBySize/sortByParams (see HfSortOption's kdoc): a repo this
    // device has never benchmarked has no honest position in a speed ordering, so it goes last
    // regardless of direction rather than being treated as infinitely fast or slow.
    private fun sortByRtf(
        results: List<HfModelSummary>,
        rtfEstimates: Map<String, Double>,
        descending: Boolean,
    ): List<HfModelSummary> {
        val (known, unknown) = results.partition { rtfEstimates[it.id] != null }
        val ascending = known.sortedBy { rtfEstimates.getValue(it.id) }
        return (if (descending) ascending.reversed() else ascending) + unknown
    }

    // The same estimator the browse UI already uses for its "~82M params" hint (ModelSpeedEstimator)
    // - one formula, applied uniformly, never a per-model lookup (spec rule 1).
    private fun paramCountOf(
        model: HfModelSummary,
        sizeEstimates: Map<String, HfSizeEstimate>,
    ): Long {
        val estimate = sizeEstimates.getValue(model.id)
        return ParameterCountEstimator.estimate(estimate.knownBytes, model.tags)
    }

    /** Every distinct tag present across [results], sorted for a stable menu - the actual choices
     * for a "filter by tag" control, derived from data rather than a hardcoded family/format list. */
    fun availableTags(results: List<HfModelSummary>): List<String> = results.flatMap { it.tags }.distinct().sorted()

    /**
     * The tag menu's *useful* choices (issue: the tag filter is slow - too many tags). A page of Hub
     * results carries hundreds of raw tags, and rendering one non-lazy menu item per tag is what
     * makes the dropdown sluggish. This trims that two ways before capping to [limit]:
     *  - drops namespaced boilerplate (`region:us`, `license:…`, `arxiv:…`, `base_model:…`, `doi:…`)
     *    - anything containing `:` - which is metadata, not something a user filters models by;
     *  - drops language codes and the `multilingual` marker, since language now has its own dedicated
     *    filter (see [HfLanguages]) and would otherwise scatter `en`/`fr`/… through the tag list.
     * What remains is ranked by how many results carry each tag (most common first, ties broken
     * alphabetically for a stable menu) and capped, so the most broadly-useful filters are the ones
     * shown. The long tail is intentionally omitted - [availableTags] still returns everything for
     * any caller that wants the full set.
     */
    fun frequentTags(
        results: List<HfModelSummary>,
        limit: Int = DEFAULT_TAG_MENU_LIMIT,
    ): List<String> {
        val languageCodes = results.flatMap { HfLanguages.codesOf(it) }.toSet()
        val counts =
            results
                .flatMap { it.tags }
                .filter { it.isNotBlank() && ':' !in it && it !in languageCodes }
                .groupingBy { it }
                .eachCount()
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    /** Keeps only results carrying [tag]; a blank/null [tag] means "no filter". */
    fun filterByTag(
        results: List<HfModelSummary>,
        tag: String?,
    ): List<HfModelSummary> = if (tag.isNullOrBlank()) results else results.filter { tag in it.tags }

    /** Keeps only results whose known download size falls within [minBytes]..[maxBytes] (either
     * bound null = unbounded on that side); no bound set at all is a no-op, unknown-size results
     * included as-is. Once a bound IS set, an unknown-size result is dropped - see [HfSizeParamFilter]. */
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

    /** Keeps only results matching [filter] against [installedIds] - the set of [HfModelSummary.id]s
     * the app already has a downloaded/resolved bundle for (computed by the caller from the local
     * model catalog; this object never touches disk). [HfInstalledFilter.ALL] is a no-op, mirroring
     * [filterByTag]'s "null/blank means no filter" shape. */
    fun filterByInstalled(
        results: List<HfModelSummary>,
        installedIds: Set<String>,
        filter: HfInstalledFilter,
    ): List<HfModelSummary> =
        when (filter) {
            HfInstalledFilter.ALL -> results
            HfInstalledFilter.INSTALLED_ONLY -> results.filter { it.id in installedIds }
            HfInstalledFilter.NOT_INSTALLED_ONLY -> results.filter { it.id !in installedIds }
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

    /**
     * Applies language, tag, size and param filtering, then [option] ordering, in that order - see
     * [HfResultFilters] for the size/language/RTF inputs, bundled to stay under detekt's parameter
     * limit. [filters] defaults to "nothing known" so the pre-existing call sites (tag-only) keep
     * compiling and behaving exactly as before.
     *
     * Deliberately NOT included: [HfInstalledFilter] - installed-ness depends on the local model
     * catalog (an app-layer concern with no representation on [HfModelSummary] itself), so callers
     * apply [filterByInstalled] themselves; filtering after sorting only removes entries, so it never
     * disturbs the order this function already computed.
     */
    fun apply(
        results: List<HfModelSummary>,
        option: HfSortOption,
        tag: String?,
        filters: HfResultFilters = HfResultFilters(),
    ): List<HfModelSummary> {
        val byLanguage = HfLanguages.filterByLanguage(results, filters.language)
        val tagged = filterByTag(byLanguage, tag)
        val sizeFilter = filters.sizeFilter
        val sized = filterBySize(tagged, filters.sizeEstimates, sizeFilter.minBytes, sizeFilter.maxBytes)
        val paramed = filterByParamCount(sized, filters.sizeEstimates, sizeFilter.minParams, sizeFilter.maxParams)
        val advanced = HfBrowseFilters.applyAdvanced(paramed, filters)
        return sort(advanced, option, filters.sizeEstimates, filters.rtfEstimates)
    }

    // A tag menu longer than this is more scroll than signal on a phone; the most common tags are
    // the useful filters, so the rest are dropped (see frequentTags). Not a model fact - a UI bound.
    private const val DEFAULT_TAG_MENU_LIMIT = 40
}
