package com.phonetts.engines.common

import com.phonetts.core.model.ModelBundle

/**
 * Fail-closed fingerprint primitive (spec §9.1): true only if [bundle] has a [fileName] side
 * file AND its content contains [marker], case-insensitively. Several engines' `inspect()`
 * recognize a family by a signature string inside a config/manifest side file - this is the one
 * place that "does the side file exist AND mention my marker" check now lives, instead of each
 * engine re-deriving it from `sideFile()` + a null check + `contains(ignoreCase = true)`.
 */
fun ModelBundle.sideFileContainsMarker(
    fileName: String,
    marker: String,
): Boolean = sideFile(fileName)?.contains(marker, ignoreCase = true) == true

/** As [sideFileContainsMarker], but confident if the side file contains ANY of [markers]. */
fun ModelBundle.sideFileContainsAnyMarker(
    fileName: String,
    markers: Collection<String>,
): Boolean {
    val content = sideFile(fileName) ?: return false
    return markers.any { marker -> content.contains(marker, ignoreCase = true) }
}
