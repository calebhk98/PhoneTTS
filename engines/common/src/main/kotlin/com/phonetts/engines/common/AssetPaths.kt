package com.phonetts.engines.common

import com.phonetts.core.model.ModelBundle

/**
 * Join a bundle's root path and a file name into an on-device asset path, tolerating a null/blank
 * root (falls back to the bare name) and a trailing slash on the root. This is the one place the
 * four slightly-different reimplementations of that idea now live.
 */
fun joinAssetPath(
    rootPath: String?,
    fileName: String,
): String {
    val root = rootPath?.trimEnd('/')
    if (root.isNullOrEmpty()) return fileName
    return "$root/$fileName"
}

/** Convenience overload: resolve [fileName] against [bundle]'s root path. */
fun joinAssetPath(
    bundle: ModelBundle,
    fileName: String,
): String = joinAssetPath(bundle.rootPath, fileName)
