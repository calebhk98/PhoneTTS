package com.phonetts.core.prefs

/**
 * A one-shot "has the first-run walkthrough been seen?" flag over an injected [PreferenceStore]
 * (same `:core` logic / `:app` SharedPreferences split as [DefaultVoicePreference]). The app gates its
 * onboarding carousel on this: shown once for a brand-new install, then never again after
 * [markSeen]. Fails safe toward *not* nagging - any stored value other than the explicit
 * "not yet seen" state counts as seen.
 */
class OnboardingState(private val store: PreferenceStore) {
    /** True once the walkthrough has been dismissed at least once. */
    fun hasSeenOnboarding(): Boolean = store.getString(SEEN_KEY) == SEEN_VALUE

    /** Records that the user has seen (and dismissed) the walkthrough, so it won't show again. */
    fun markSeen() {
        store.putString(SEEN_KEY, SEEN_VALUE)
    }

    companion object {
        private const val SEEN_KEY = "onboarding_seen"
        private const val SEEN_VALUE = "true"
    }
}
