package com.phonetts.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to the system package installer - the last step of the in-app update
 * (offer, never force: this only runs when the user taps Install, and the OS still shows its own
 * confirm screen, so nothing is ever installed behind the user's back).
 *
 * The file lives in app-private cache, so it is shared as a `content://` URI through the app's
 * FileProvider with a one-shot read grant; a `file://` URI would throw `FileUriExposedException` on
 * Android 7+.
 *
 * On Android 8+ installing from here also needs the user's per-app "install unknown apps" consent.
 * We check for it first and send the user straight to that settings page when it's missing, instead
 * of firing an install intent that silently dead-ends.
 */
object ApkInstaller {
    /** True when the OS will let this app request an install right now (always true below API 26). */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * Launch the installer for [apkPath]. Returns false when the file has gone missing (cache
     * eviction) so the caller can offer the download again rather than show nothing.
     */
    fun install(
        context: Context,
        apkPath: String,
    ): Boolean {
        val apk = File(apkPath)
        if (!apk.isFile) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$PROVIDER_SUFFIX", apk)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        return true
    }

    /** Open this app's "install unknown apps" toggle so the user can grant it, then come back. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private const val PROVIDER_SUFFIX = ".fileprovider"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
