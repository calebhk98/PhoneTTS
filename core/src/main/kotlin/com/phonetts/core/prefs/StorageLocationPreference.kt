package com.phonetts.core.prefs

/**
 * Where downloaded/sideloaded model weights live (issue #4/#5): app-private storage by default
 * (wiped on uninstall), or a user-picked folder — internal shared storage or an SD card — so
 * weights SURVIVE an uninstall/reinstall. `:core` only ever remembers a plain absolute path
 * string; all the Storage Access Framework/URI/permission plumbing that produces that path lives
 * in `:app` (spec: `:core` stays Android-free, takes plain `java.io.File`).
 *
 * [customTreeUri] is kept only as a receipt of which folder the user picked (so the app layer can
 * re-verify/re-request access on a later launch); the actual model directory scanning always goes
 * through [customBasePath], a plain filesystem path.
 */
class StorageLocationPreference(private val store: PreferenceStore) {
    /** The user-chosen absolute path to use as the models base dir, or null for the app-private default. */
    fun customBasePath(): String? = store.getString(BASE_PATH_KEY)

    /** Records [absolutePath] as the models base dir going forward, or clears it back to the default. */
    fun setCustomBasePath(absolutePath: String?) = putOrRemove(BASE_PATH_KEY, absolutePath)

    /** The SAF tree URI (as a string) the current [customBasePath] was resolved from, if any. */
    fun customTreeUri(): String? = store.getString(TREE_URI_KEY)

    /** Records the SAF tree URI backing the current custom location, or clears it. */
    fun setCustomTreeUri(uri: String?) = putOrRemove(TREE_URI_KEY, uri)

    private fun putOrRemove(
        key: String,
        value: String?,
    ) {
        if (value == null) store.remove(key) else store.putString(key, value)
    }

    companion object {
        private const val BASE_PATH_KEY = "model_storage_custom_base_path"
        private const val TREE_URI_KEY = "model_storage_custom_tree_uri"
    }
}
