package com.phonetts.core.update

/**
 * A minimal MAJOR.MINOR.PATCH version, tolerant of a leading `v` (release tags are `v0.1.2`, the
 * app's `BuildConfig.VERSION_NAME` is `0.1.2`). Only the numeric core is compared — any pre-release
 * suffix is ignored — which is all the update check needs. [parse] fails closed (returns null) on
 * anything it can't read, so a malformed tag never triggers a bogus "update available".
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String): SemVer? {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            // Take the numeric core before any pre-release/build suffix (e.g. "0.1.2-rc1" -> "0.1.2").
            val core = trimmed.takeWhile { it.isDigit() || it == '.' }
            val parts = core.split('.')
            if (parts.size < 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return SemVer(major, minor, patch)
        }
    }
}
