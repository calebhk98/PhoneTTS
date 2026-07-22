package com.phonetts.core.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorageLocationPreferenceTest {
    @Test
    fun defaultsToNoCustomLocation() {
        val pref = StorageLocationPreference(InMemoryPreferenceStore())

        assertNull(pref.customBasePath())
        assertNull(pref.customTreeUri())
    }

    @Test
    fun savedBasePathAndTreeUriRoundTrip() {
        val pref = StorageLocationPreference(InMemoryPreferenceStore())

        pref.setCustomBasePath("/storage/1234-5678/PhoneTTS/models")
        pref.setCustomTreeUri("content://com.android.externalstorage.documents/tree/1234-5678%3APhoneTTS")

        assertEquals("/storage/1234-5678/PhoneTTS/models", pref.customBasePath())
        assertEquals(
            "content://com.android.externalstorage.documents/tree/1234-5678%3APhoneTTS",
            pref.customTreeUri(),
        )
    }

    @Test
    fun clearingBasePathRevertsToTheDefault() {
        val pref = StorageLocationPreference(InMemoryPreferenceStore())
        pref.setCustomBasePath("/sdcard/whatever")

        pref.setCustomBasePath(null)

        assertNull(pref.customBasePath())
    }

    @Test
    fun clearingTreeUriDropsTheReceiptOnly() {
        val pref = StorageLocationPreference(InMemoryPreferenceStore())
        pref.setCustomBasePath("/sdcard/whatever")
        pref.setCustomTreeUri("content://tree/x")

        pref.setCustomTreeUri(null)

        assertEquals("/sdcard/whatever", pref.customBasePath())
        assertNull(pref.customTreeUri())
    }
}
