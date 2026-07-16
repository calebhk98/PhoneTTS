package com.phonetts.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {
    @Test
    fun parsesWithAndWithoutTheLeadingV() {
        assertEquals(SemVer(0, 1, 2), SemVer.parse("v0.1.2"))
        assertEquals(SemVer(0, 1, 2), SemVer.parse("0.1.2"))
        assertEquals(SemVer(1, 0, 0), SemVer.parse("1.0"), "missing patch defaults to 0")
    }

    @Test
    fun ignoresAPrereleaseSuffix() {
        assertEquals(SemVer(0, 1, 2), SemVer.parse("0.1.2-rc1"))
    }

    @Test
    fun failsClosedOnGarbage() {
        assertNull(SemVer.parse("not-a-version"))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("v1"))
    }

    @Test
    fun ordersByMajorThenMinorThenPatch() {
        assertTrue(SemVer(0, 2, 0) > SemVer(0, 1, 9))
        assertTrue(SemVer(0, 1, 10) > SemVer(0, 1, 9))
        assertTrue(SemVer(1, 0, 0) > SemVer(0, 9, 9))
        assertEquals(SemVer(0, 1, 2), SemVer(0, 1, 2))
    }
}
