package com.phonetts.app.text

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File

/**
 * Copies `assets/espeak-ng-data/` (see docs/espeak-ng-integration.md for how that directory is
 * produced from the espeak-ng build) into app-private storage on first run, since espeak-ng
 * needs a real filesystem path — it cannot read its data tables out of the APK's compressed
 * asset store (docs/research/espeak-ng.md §3.3, §6.5).
 *
 * Re-copies are skipped once a [MARKER_FILE] is present, so this is cheap on every launch after
 * the first. There's deliberately no version check against the app's version code: if the
 * bundled data ever changes, bump [MARKER_FILE]'s contents check below, or simplest — delete the
 * marker as part of that change so the next launch re-copies.
 */
internal class EspeakDataInstaller(private val context: Context) {
    /** Returns the absolute path to the installed data dir, or null if assets aren't present. */
    fun install(): String? {
        val targetDir = File(context.filesDir, DATA_DIR_NAME)
        val marker = File(targetDir, MARKER_FILE)
        if (marker.exists()) return targetDir.absolutePath

        val assets = context.assets
        if (!hasEspeakAssets(assets)) {
            Log.w(TAG, "no '$DATA_DIR_NAME' found under assets/ -- espeak-ng cannot initialize")
            return null
        }

        targetDir.mkdirs()
        copyAssetDirRecursively(assets, DATA_DIR_NAME, targetDir)
        marker.writeText("installed")
        return targetDir.absolutePath
    }

    private fun hasEspeakAssets(assets: AssetManager): Boolean {
        val entries = runCatching { assets.list(DATA_DIR_NAME) }.getOrNull()
        return !entries.isNullOrEmpty()
    }

    private fun copyAssetDirRecursively(
        assets: AssetManager,
        assetPath: String,
        destDir: File,
    ) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            copyAssetFile(assets, assetPath, destDir)
            return
        }
        destDir.mkdirs()
        for (child in children) {
            copyAssetDirRecursively(assets, "$assetPath/$child", File(destDir, child))
        }
    }

    private fun copyAssetFile(
        assets: AssetManager,
        assetPath: String,
        destFile: File,
    ) {
        destFile.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private companion object {
        const val TAG = "EspeakDataInstaller"
        const val DATA_DIR_NAME = "espeak-ng-data"
        const val MARKER_FILE = ".installed"
    }
}
