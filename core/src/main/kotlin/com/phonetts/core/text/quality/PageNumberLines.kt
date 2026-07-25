package com.phonetts.core.text.quality

/**
 * The single definition of "this line is nothing but a page number" (SSOT - both
 * [ImportQualityAnalyzer], which counts these lines to detect a badly-structured import, and
 * [DocumentCleanup], which drops them, need the exact same rule; duplicating the regex in both
 * places would be exactly the kind of literal-fact duplication the project's SSOT rule forbids).
 *
 * Matches bare digits ("42"), "Page 3", "Page 3 of 10", "3 of 10", and the dashed style some
 * exports use ("- 3 -").
 */
internal val pageNumberLinePattern =
    Regex("""^\d{1,4}$|^(?i:page)\s+\d+(\s+of\s+\d+)?$|^\d+\s+of\s+\d+$|^-\s*\d+\s*-$""")
