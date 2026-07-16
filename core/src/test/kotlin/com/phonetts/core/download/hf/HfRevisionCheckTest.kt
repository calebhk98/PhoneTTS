package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HfRevisionCheckTest {
    private val modelId = "hexgrad/Kokoro-82M"

    private fun infoJson(sha: String) = """{"id":"$modelId","sha":"$sha"}"""

    @Test
    fun requestsTheModelInfoEndpointForTheGivenModelId() {
        val http = FakeHttpClient(listOf("/api/models/" to infoJson("abc123")))
        HfRevisionCheck(http).fetchModelInfo(modelId)

        assertEquals(
            "https://huggingface.co/api/models/hexgrad/Kokoro-82M",
            http.requested.single(),
        )
    }

    @Test
    fun reportsNoUpdateWhenTheStoredRevisionMatchesTheCurrentOne() {
        val http = FakeHttpClient(listOf("/api/models/" to infoJson("abc123")))
        val status = HfRevisionCheck(http).check(modelId, installedRevision = "abc123")

        assertFalse(status.updateAvailable)
        assertEquals("abc123", status.currentRevision)
    }

    @Test
    fun reportsAnUpdateWhenTheCurrentCommitDiffersFromTheStoredOne() {
        val http = FakeHttpClient(listOf("/api/models/" to infoJson("newsha456")))
        val status = HfRevisionCheck(http).check(modelId, installedRevision = "oldsha123")

        assertTrue(status.updateAvailable)
        assertEquals("newsha456", status.currentRevision)
        assertEquals("oldsha123", status.installedRevision)
    }

    @Test
    fun failsClosedToNoUpdateWhenTheHubReportsNoSha() {
        val http = FakeHttpClient(listOf("/api/models/" to """{"id":"$modelId"}"""))
        val status = HfRevisionCheck(http).check(modelId, installedRevision = "abc123")

        assertFalse(status.updateAvailable)
        assertEquals(null, status.currentRevision)
    }

    @Test
    fun ignoresUnknownFieldsInTheModelInfoBody() {
        val http =
            FakeHttpClient(
                listOf("/api/models/" to """{"id":"$modelId","sha":"abc123","private":false,"downloads":900}"""),
            )
        val info = HfRevisionCheck(http).fetchModelInfo(modelId)

        assertEquals("abc123", info.sha)
    }
}
