package com.phonetts.core.update

import com.phonetts.core.download.hf.FakeHttpClient
import com.phonetts.core.download.hf.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-app update check (offer, never force). Pure JVM over a fake [HttpClient] + fixture JSON:
 * it flags a newer release only when one exists AND ships an installable `.apk`, picks the highest
 * semver (not merely the first listed), includes prereleases (our auto-published builds are
 * prereleases), and fails CLOSED — any hiccup yields "up to date" rather than a bogus prompt.
 */
class UpdateCheckerTest {
    private val owner = "calebhk98"
    private val repo = "phonetts"

    private fun release(
        tag: String,
        apk: String? = "phonetts-$tag.apk",
        prerelease: Boolean = true,
        draft: Boolean = false,
    ): String {
        val assets =
            if (apk == null) {
                "[]"
            } else {
                """[{"name":"$apk","browser_download_url":"https://gh/dl/$tag/$apk"}]"""
            }
        return """{"tag_name":"$tag","html_url":"https://gh/rel/$tag","draft":$draft,""" +
            """"prerelease":$prerelease,"assets":$assets}"""
    }

    private fun checkerFor(vararg releasesJson: String): UpdateChecker {
        val body = "[" + releasesJson.joinToString(",") + "]"
        return UpdateChecker(FakeHttpClient(listOf("/releases" to body)))
    }

    @Test
    fun offersAnUpdateWhenANewerReleaseWithAnApkExists() {
        val status = checkerFor(release("v0.1.5"), release("v0.1.4")).check("0.1.3", owner, repo)

        assertTrue(status.updateAvailable)
        assertEquals("0.1.5", status.latestVersion)
        assertEquals("0.1.3", status.currentVersion)
        assertEquals("https://gh/dl/v0.1.5/phonetts-v0.1.5.apk", status.apkDownloadUrl)
        assertEquals("https://gh/rel/v0.1.5", status.releasePageUrl)
    }

    @Test
    fun picksTheHighestSemverNotTheFirstListed() {
        val status = checkerFor(release("v0.1.2"), release("v0.2.0"), release("v0.1.9")).check("0.1.5", owner, repo)

        assertEquals("0.2.0", status.latestVersion)
    }

    @Test
    fun reportsUpToDateWhenLatestEqualsCurrent() {
        val status = checkerFor(release("v0.1.3")).check("0.1.3", owner, repo)

        assertFalse(status.updateAvailable)
        assertNull(status.apkDownloadUrl)
    }

    @Test
    fun reportsUpToDateWhenLatestIsOlderThanCurrent() {
        val status = checkerFor(release("v0.1.1")).check("0.1.4", owner, repo)

        assertFalse(status.updateAvailable)
    }

    @Test
    fun ignoresReleasesThatShipNoApk() {
        // Newest by version has no APK; the older one that does is not newer than current -> no offer.
        val status = checkerFor(release("v0.2.0", apk = null), release("v0.1.0")).check("0.1.4", owner, repo)

        assertFalse(status.updateAvailable, "a release with no installable APK must not be offered")
    }

    @Test
    fun failsClosedOnANetworkError() {
        val throwing =
            object : HttpClient {
                override fun getText(
                    url: String,
                    headers: Map<String, String>,
                ): String = throw java.io.IOException("offline")
            }

        val status = UpdateChecker(throwing).check("0.1.3", owner, repo)

        assertFalse(status.updateAvailable, "a network hiccup must never surface a bogus update")
        assertEquals("0.1.3", status.currentVersion)
    }

    @Test
    fun failsClosedOnAMalformedCurrentVersion() {
        val status = checkerFor(release("v0.1.9")).check("not-a-version", owner, repo)

        assertFalse(status.updateAvailable)
    }
}
