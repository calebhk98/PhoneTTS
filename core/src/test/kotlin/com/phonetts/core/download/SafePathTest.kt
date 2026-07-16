package com.phonetts.core.download

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafePathTest {
    @Test
    fun acceptsNormalRelativePaths() {
        assertTrue(SafePath.isSafe("config.json"))
        assertTrue(SafePath.isSafe("onnx/model.onnx"))
        assertTrue(SafePath.isSafe("a/b/c.txt"))
    }

    @Test
    fun rejectsTraversalAbsoluteAndBlankPaths() {
        assertFalse(SafePath.isSafe("../secret"))
        assertFalse(SafePath.isSafe("a/../../etc/passwd"))
        assertFalse(SafePath.isSafe("/absolute"))
        assertFalse(SafePath.isSafe("""\windows"""))
        assertFalse(SafePath.isSafe("C:/drive"))
        assertFalse(SafePath.isSafe(""))
        assertFalse(SafePath.isSafe("a//b")) // empty segment
    }

    @Test
    fun requireThrowsOnUnsafePath() {
        assertFailsWith<IllegalArgumentException> { SafePath.require("../../escape") }
    }
}
