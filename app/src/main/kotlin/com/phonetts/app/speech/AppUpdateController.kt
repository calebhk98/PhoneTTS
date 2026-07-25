package com.phonetts.app.speech

import com.phonetts.app.AppGraph
import com.phonetts.app.BuildConfig
import com.phonetts.core.update.ApkDownloadState
import com.phonetts.core.update.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The in-app update checker/downloader (offer, never force) - split out of `TtsViewModel` so the
 * update flow's own state machine (checking → offered → downloading → done) is independently
 * testable and reviewable, instead of six more fields/methods on the god object (mission).
 *
 * Wraps [com.phonetts.core.update.UpdateChecker] (fail-closed: a network hiccup leaves [State.update]
 * null) and [com.phonetts.app.update.ApkUpdateDownloader] (fetches the APK itself, with progress and
 * SHA-256 verification, rather than handing GitHub's short-lived signed asset URL to the browser).
 */
class AppUpdateController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    data class State(
        val update: UpdateStatus? = null,
        val updateCheckStatus: String? = null,
        val updateDownload: ApkDownloadState = ApkDownloadState.Idle,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var downloadJob: Job? = null

    /** Ask GitHub Releases whether a newer APK exists. [announce] = true (the Help screen's manual
     * "Check for updates" button) also reports "up to date"/a failure; the silent launch check
     * (announce = false) only ever surfaces a strictly-newer release. */
    fun checkForUpdate(announce: Boolean) {
        if (announce) mutableState.update { it.copy(updateCheckStatus = "Checking for updates…") }
        scope.launch(Dispatchers.IO) {
            val status =
                runCatching {
                    graph.updateChecker.check(BuildConfig.VERSION_NAME, AppGraph.REPO_OWNER, AppGraph.REPO_NAME)
                }.getOrNull()
            mutableState.update {
                when {
                    status == null -> if (announce) it.copy(updateCheckStatus = "Couldn't check - no connection?") else it
                    status.updateAvailable -> it.copy(update = status, updateCheckStatus = null)
                    announce -> it.copy(updateCheckStatus = "Up to date (v${status.currentVersion})")
                    else -> it
                }
            }
        }
    }

    /** Download the offered APK in-app, with progress, leaving it ready to install. */
    fun downloadUpdate() {
        val status = mutableState.value.update ?: return
        if (mutableState.value.updateDownload is ApkDownloadState.Downloading) return
        downloadJob?.cancel()
        downloadJob =
            scope.launch {
                graph.apkUpdateDownloader.download(status) { state -> mutableState.update { it.copy(updateDownload = state) } }
            }
    }

    /** Abandon an in-flight download; no partial file is kept. */
    fun cancelUpdateDownload() {
        downloadJob?.cancel()
        downloadJob = null
        mutableState.update { it.copy(updateDownload = ApkDownloadState.Idle) }
    }

    /** Dismiss the update banner for this session (the check runs again next launch). */
    fun dismissUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        scope.launch(Dispatchers.IO) { graph.apkUpdateDownloader.clear() }
        mutableState.update { it.copy(update = null, updateDownload = ApkDownloadState.Idle) }
    }
}
