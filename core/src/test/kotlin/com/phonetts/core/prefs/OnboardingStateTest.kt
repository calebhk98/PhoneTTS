package com.phonetts.core.prefs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingStateTest {
    @Test
    fun `a fresh install has not seen onboarding`() {
        val state = OnboardingState(InMemoryPreferenceStore())
        assertFalse(state.hasSeenOnboarding())
    }

    @Test
    fun `markSeen makes the walkthrough count as seen`() {
        val state = OnboardingState(InMemoryPreferenceStore())
        state.markSeen()
        assertTrue(state.hasSeenOnboarding())
    }

    @Test
    fun `seen state persists across new instances over the same store`() {
        val store = InMemoryPreferenceStore()
        OnboardingState(store).markSeen()
        assertTrue(OnboardingState(store).hasSeenOnboarding())
    }
}
