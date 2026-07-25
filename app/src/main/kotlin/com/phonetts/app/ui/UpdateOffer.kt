package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.phonetts.app.update.ApkInstaller
import com.phonetts.core.format.ByteFormat
import com.phonetts.core.update.ApkDownloadState
import com.phonetts.core.update.UpdateStatus
import com.phonetts.core.update.percentOrNull

/**
 * The one place the update offer's actions are rendered - shared by the Home banner and the Help
 * screen's "About & updates" section, so there is a single definition of what Download/Install mean
 * (the two used to each hand the URL to the browser separately).
 *
 * The app downloads the APK itself now, with real progress, because delegating to the browser is
 * exactly what broke: GitHub's asset URL 302s to a signed link that expires within the hour, so a
 * browser download that queues or retries past that never completes. "Open in browser" survives only
 * as an explicit fallback, and as the offered recovery when an in-app download fails.
 *
 * Still offer-never-force: nothing downloads until Download is tapped, nothing installs until
 * Install is tapped, and the OS's own installer confirmation is the final gate.
 */
@Composable
fun UpdateActions(
    update: UpdateStatus,
    download: ApkDownloadState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val browserTarget = update.apkDownloadUrl ?: update.releasePageUrl
    var note by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (download) {
            is ApkDownloadState.Idle ->
                IdleActions(update, onDownload) { browserTarget?.let(uriHandler::openUri) }

            is ApkDownloadState.Downloading -> DownloadingActions(download, onCancelDownload)

            is ApkDownloadState.Verifying -> {
                Text("Verifying download…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is ApkDownloadState.Ready ->
                ReadyActions(download) { message -> note = message }

            is ApkDownloadState.Failed -> {
                Text(download.reason, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) { Text("Retry") }
                    TextButton(
                        onClick = { browserTarget?.let(uriHandler::openUri) },
                        enabled = browserTarget != null,
                    ) { Text("Open in browser") }
                }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    // Re-check consent every recomposition rather than caching it: the user may have just granted it
    // in Settings and come straight back.
    if (download is ApkDownloadState.Ready && !ApkInstaller.canInstall(context)) {
        Text(
            "Android needs permission to install apps from PhoneTTS - tap Install and allow it.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun IdleActions(
    update: UpdateStatus,
    onDownload: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDownload, enabled = update.apkDownloadUrl != null) {
            Text(
                "Download ${update.latestVersion}" +
                    (update.apkSizeBytes?.let { " (${ByteFormat.format(it)})" } ?: ""),
            )
        }
        TextButton(onClick = onOpenInBrowser) { Text("Open in browser") }
    }
}

@Composable
private fun DownloadingActions(
    download: ApkDownloadState.Downloading,
    onCancelDownload: () -> Unit,
) {
    val percent = download.percentOrNull()
    Text(progressLabel(download, percent), style = MaterialTheme.typography.bodySmall)
    // Determinate only when the release advertised a size - never fake a percentage.
    if (percent == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { percent / PERCENT_SCALE }, modifier = Modifier.fillMaxWidth())
    }
    OutlinedButton(onClick = onCancelDownload) { Text("Cancel") }
}

@Composable
private fun ReadyActions(
    download: ApkDownloadState.Ready,
    onNote: (String) -> Unit,
) {
    val context = LocalContext.current
    val readyLabel =
        if (download.verified) {
            "Downloaded and checksum-verified - ready to install."
        } else {
            "Downloaded (this release advertised no checksum, so it couldn't be verified)."
        }
    Text(readyLabel, style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = {
            if (!ApkInstaller.canInstall(context)) {
                ApkInstaller.requestInstallPermission(context)
                onNote("Allow \"install unknown apps\" for PhoneTTS, then tap Install again.")
                return@Button
            }
            val started = ApkInstaller.install(context, download.path)
            if (!started) onNote("The downloaded file is gone - tap Download again.")
        },
    ) { Text("Install") }
}

private fun progressLabel(
    download: ApkDownloadState.Downloading,
    percent: Int?,
): String {
    val done = ByteFormat.format(download.bytesDone)
    val total = download.bytesTotal?.let { " of ${ByteFormat.format(it)}" } ?: ""
    val prefix = percent?.let { "$it% - " } ?: "Downloading - "
    return "$prefix$done$total"
}

private const val PERCENT_SCALE = 100f
