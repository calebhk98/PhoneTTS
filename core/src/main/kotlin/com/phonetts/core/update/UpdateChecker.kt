package com.phonetts.core.update

import com.phonetts.core.download.hf.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Checks GitHub Releases for a newer PhoneTTS build than the one running, and — if there is one —
 * returns where to get it. This only ever *offers* an update; installing is the user's choice
 * (CLAUDE.md / the app never force-updates). Pure JVM and testable against a fake [HttpClient] +
 * fixture JSON; the real transport lives in :app.
 *
 * Fail-closed: any error (no network, bad JSON, no parseable version, no APK asset) yields
 * [UpdateStatus.upToDate] rather than a bogus prompt — an update check must never nag on a hiccup.
 */
class UpdateChecker(
    private val http: HttpClient,
    private val json: Json = DEFAULT_JSON,
) {
    /**
     * @param currentVersion the running app's version (e.g. `BuildConfig.VERSION_NAME`, `"0.1.3"`).
     * @return an [UpdateStatus]; [UpdateStatus.updateAvailable] is true only when a release with a
     *   strictly-greater [SemVer] AND a downloadable `.apk` asset exists.
     */
    fun check(
        currentVersion: String,
        owner: String,
        repo: String,
    ): UpdateStatus {
        val current = SemVer.parse(currentVersion) ?: return UpdateStatus.upToDate(currentVersion)
        val releases =
            runCatching {
                val body = http.getText(releasesUrl(owner, repo), USER_AGENT)
                json.decodeFromString<List<GithubReleaseDto>>(body)
            }.getOrNull() ?: return UpdateStatus.upToDate(currentVersion)

        // Pick the highest parseable, non-draft release that actually ships an installable APK
        // (prereleases included — the auto-published builds are prereleases).
        val newest =
            releases
                .asSequence()
                .filterNot { it.draft }
                .mapNotNull { release ->
                    val version = SemVer.parse(release.tagName) ?: return@mapNotNull null
                    val apk =
                        release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                            ?: return@mapNotNull null
                    Candidate(version, apk.downloadUrl, release.htmlUrl)
                }
                .maxByOrNull { it.version }
                ?: return UpdateStatus.upToDate(currentVersion)

        if (newest.version <= current) return UpdateStatus.upToDate(currentVersion)
        return UpdateStatus(
            updateAvailable = true,
            currentVersion = current.toString(),
            latestVersion = newest.version.toString(),
            apkDownloadUrl = newest.apkUrl,
            releasePageUrl = newest.releaseUrl,
        )
    }

    private fun releasesUrl(
        owner: String,
        repo: String,
    ): String = "https://api.github.com/repos/$owner/$repo/releases?per_page=$PAGE_SIZE"

    private data class Candidate(val version: SemVer, val apkUrl: String, val releaseUrl: String)

    companion object {
        private const val PAGE_SIZE = 20
        val USER_AGENT = mapOf("User-Agent" to "PhoneTTS (offline-tts-android; update-check)")
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
    }
}

/** The result of an [UpdateChecker.check]. [updateAvailable] false means "you're current" (or a hiccup). */
data class UpdateStatus(
    val updateAvailable: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val apkDownloadUrl: String? = null,
    val releasePageUrl: String? = null,
) {
    companion object {
        fun upToDate(currentVersion: String): UpdateStatus =
            UpdateStatus(updateAvailable = false, currentVersion = currentVersion, latestVersion = currentVersion)
    }
}

// The subset of the GitHub Releases API this needs; unknown fields are ignored (DEFAULT_JSON).
@Serializable
internal data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("assets") val assets: List<GithubAssetDto> = emptyList(),
)

@Serializable
internal data class GithubAssetDto(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
)
