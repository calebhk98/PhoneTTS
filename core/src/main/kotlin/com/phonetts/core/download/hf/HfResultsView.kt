package com.phonetts.core.download.hf

/**
 * Ways to order a list of [HfModelSummary] search results. Every option reads a field the Hub
 * already returned on the summary — no model list or family is named here (issue #6 SSOT rule:
 * sort/filter options must derive from data, never a hardcoded catalog).
 */
enum class HfSortOption {
    /** As the Hub returned them (its own relevance/downloads ranking for the query). */
    RELEVANCE,
    MOST_DOWNLOADS,
    MOST_LIKES,
    NAME_ASC,
}

/**
 * Sorts and filters Hub search results. A filter's *available choices* (see [availableTags]) are
 * always computed from the current result set, not a fixed list — so a tag the Hub starts using
 * tomorrow shows up as a filter option with no code change here (issue #6).
 */
object HfResultsView {
    fun sort(
        results: List<HfModelSummary>,
        option: HfSortOption,
    ): List<HfModelSummary> =
        when (option) {
            HfSortOption.RELEVANCE -> results
            HfSortOption.MOST_DOWNLOADS -> results.sortedByDescending { it.downloads }
            HfSortOption.MOST_LIKES -> results.sortedByDescending { it.likes }
            HfSortOption.NAME_ASC -> results.sortedBy { it.id.lowercase() }
        }

    /** Every distinct tag present across [results], sorted for a stable menu — the actual choices
     * for a "filter by tag" control, derived from data rather than a hardcoded family/format list. */
    fun availableTags(results: List<HfModelSummary>): List<String> = results.flatMap { it.tags }.distinct().sorted()

    /** Keeps only results carrying [tag]; a blank/null [tag] means "no filter". */
    fun filterByTag(
        results: List<HfModelSummary>,
        tag: String?,
    ): List<HfModelSummary> = if (tag.isNullOrBlank()) results else results.filter { tag in it.tags }

    /** Applies [tag] filtering and then [option] ordering, in that order. */
    fun apply(
        results: List<HfModelSummary>,
        option: HfSortOption,
        tag: String?,
    ): List<HfModelSummary> = sort(filterByTag(results, tag), option)
}
