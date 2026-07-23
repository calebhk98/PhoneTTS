package com.phonetts.core.compare

// Pure scheduling helpers behind Compare's tournament look-ahead prefetch (issue #112) and its
// within-round progress counter (issue #113). Both are deliberately app-independent so the awkward
// "how far ahead do we generate?" and "how many comparisons are in this round?" decisions are
// decided (and exhaustively tested) here in :core, not buried inside a ViewModel.

/**
 * Decide how many upcoming, not-yet-generated tournament entries may be generated AHEAD of the one
 * the user is currently judging, given how much spare ("banked") time we have and how expensive each
 * upcoming entry is to generate (issue #112).
 *
 * Model: while the user listens to and deliberates over the current pairing - and again every time
 * they replay an example - wall-clock time passes during which nothing else needs the (single, spec
 * rule 6) engine, so that time is "banked". Generating one upcoming entry spends banked seconds
 * equal to its estimated generation time. We greedily prefetch entries in order while their
 * cumulative generation cost still fits inside [bankedSeconds]:
 *
 *  - a model that generates FASTER than real time leaves time in the bank, so several of the
 *    following entries become affordable and we run several ahead;
 *  - a SLOWER-than-real-time model drains the bank, so the look-ahead naturally shrinks back toward
 *    [minAhead] rather than piling up unbounded work.
 *
 * The result is clamped to `[minAhead, maxAhead]` (both first clamped into `0..upcoming.size`), so
 * the immediate next entry is always fetched even with an empty bank ([minAhead]), and memory /
 * serialized-generation pressure stays bounded ([maxAhead]). Pure and total: negative costs are
 * treated as zero, an empty list yields the clamped [minAhead] (i.e. 0).
 */
fun prefetchAhead(
    bankedSeconds: Double,
    upcomingGenerationSeconds: List<Double>,
    minAhead: Int,
    maxAhead: Int,
): Int {
    val available = upcomingGenerationSeconds.size
    val floor = minAhead.coerceIn(0, available)
    val ceiling = maxAhead.coerceIn(floor, available)

    var spent = 0.0
    var affordable = 0
    for (cost in upcomingGenerationSeconds) {
        val next = spent + cost.coerceAtLeast(0.0)
        // Stop at the ceiling, or once the next entry would overspend the bank (it is not counted).
        if (affordable >= ceiling || next > bankedSeconds) break
        spent = next
        affordable++
    }
    return affordable.coerceIn(floor, ceiling)
}

/**
 * The number of head-to-head COMPARISONS in each round of a single-elimination bracket seeded with
 * [entryCount] contenders, round 1 first (issue #113's "(X/N)" progress counter needs the N).
 *
 * Mirrors [Tournament]'s own pairing rule exactly: each round pairs adjacent survivors (so a round
 * of `n` has `n / 2` comparisons) and an odd contender out takes a bye (advancing without a
 * comparison, hence integer division), leaving `(n + 1) / 2` survivors for the next round. A field
 * of fewer than two has no comparisons at all, so an empty list is returned.
 *
 * Example: 5 entries -> `[2, 1, 1]` (2 comparisons + 1 bye, then 1 + 1 bye, then the final).
 */
fun bracketRoundSizes(entryCount: Int): List<Int> {
    val sizes = mutableListOf<Int>()
    var alive = entryCount
    while (alive >= 2) {
        sizes += alive / 2
        alive = (alive + 1) / 2
    }
    return sizes
}
