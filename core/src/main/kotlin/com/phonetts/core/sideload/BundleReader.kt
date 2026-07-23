package com.phonetts.core.sideload

import com.phonetts.core.model.ModelBundle

/**
 * Turns a user-picked location into a [ModelBundle] the resolver can inspect. The location is
 * platform-specific - a filesystem directory path on the JVM, a Storage Access Framework tree
 * URI on Android - so this is an interface: [DirectoryBundleReader] handles a plain directory
 * (and is what the seam tests use), while the Android SAF-backed reader lives in :app.
 */
interface BundleReader {
    fun read(location: String): ModelBundle
}
