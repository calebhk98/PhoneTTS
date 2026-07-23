package com.phonetts.core.download.hf

/**
 * Guesses which registered engine (if any) a browsed repo would map to, for the "Engine type"
 * browse filter (issue #107). A browsed repo isn't downloaded yet, so the authoritative resolver
 * (`inspect()`) can't run - this is a pre-download heuristic over the repo id + its Hub tags, using
 * the SET OF ENGINE IDS the caller supplies from the live registry (never a hardcoded model list,
 * spec rule 1). A repo matching none is bucketed under [UNKNOWN] ("Other").
 *
 * Matching is token-normalized (letters/digits only, case-insensitive): an engine whose normalized
 * id appears in the normalized repo id, or in any normalized tag, claims it. The first engine in
 * the supplied order wins, so the result is deterministic. Pure and unit-tested in `:core`.
 */
object HfEngineClassifier {
    /** The bucket for a repo no known engine's id matches. A generic UI label, not a model fact. */
    const val UNKNOWN = "Other"

    /** The engine id that claims [model], or [UNKNOWN] if none of [engineIds] matches. */
    fun engineOf(
        model: HfModelSummary,
        engineIds: List<String>,
    ): String {
        val haystack = normalize(model.id) + " " + model.tags.joinToString(" ") { normalize(it) }
        return engineIds.firstOrNull { id ->
            val token = normalize(id)
            token.isNotEmpty() && token in haystack
        } ?: UNKNOWN
    }

    /** A label per result id (matched engine id, or [UNKNOWN]) - the input the browse filter and
     * its available-choices menu both read, so both agree on every model's bucket. */
    fun engineLabels(
        results: List<HfModelSummary>,
        engineIds: List<String>,
    ): Map<String, String> = results.associate { it.id to engineOf(it, engineIds) }

    /** The engine-filter menu's choices: every engine label actually present, matched engines first
     * (alphabetically) and [UNKNOWN] last when any result fell through to it. */
    fun availableEngines(labelsById: Map<String, String>): List<String> {
        val present = labelsById.values.distinct()
        val matched = present.filter { it != UNKNOWN }.sorted()
        return if (UNKNOWN in present) matched + UNKNOWN else matched
    }

    /** Keeps only results whose engine label equals [selected]; a null [selected] is a no-op. */
    fun filterByEngine(
        results: List<HfModelSummary>,
        labelsById: Map<String, String>,
        selected: String?,
    ): List<HfModelSummary> {
        if (selected == null) return results
        return results.filter { (labelsById[it.id] ?: UNKNOWN) == selected }
    }

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
