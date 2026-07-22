package com.phonetts.core.download.hf

/**
 * Decides whether the browse screen's "Load more" should fetch another page of Hub search results
 * (issue: with a restrictive filter active, one page mostly gets filtered away and the button looks
 * broken — appearing to do nothing). Pure decision logic only: the actual network fetch, and the
 * "how many results are visible after filtering" count, both live in `:app` (network + the local
 * install/benchmark state this needs aren't available in `:core`); this object just says stop/go
 * given numbers the caller already has, so the policy itself is unit-tested without a fake HTTP
 * client or Android.
 */
object HfLoadMorePolicy {
    /**
     * @param pagesFetchedSoFar how many pages THIS load-more call has already fetched (0 before the
     * first fetch).
     * @param lastPageSize the size of the most recently fetched page — [Int.MAX_VALUE] (i.e. "assume
     * there's more") before any page has been fetched yet.
     * @param pageSize the Hub page size; a page shorter than this means no more results exist.
     * @param newlyVisibleCount how many MORE post-filter results are visible now vs. before this
     * load-more call started.
     * @param targetNewVisible stop once at least this many new post-filter results have appeared.
     * @param maxPages a hard cap on pages fetched per call, so a filter matching almost nothing (or a
     * query with few genuine results) can't spin fetching pages forever.
     */
    fun shouldFetchAnotherPage(
        pagesFetchedSoFar: Int,
        lastPageSize: Int,
        pageSize: Int,
        newlyVisibleCount: Int,
        targetNewVisible: Int,
        maxPages: Int,
    ): Boolean {
        if (pagesFetchedSoFar >= maxPages) return false
        if (lastPageSize < pageSize) return false
        return newlyVisibleCount < targetNewVisible
    }
}
