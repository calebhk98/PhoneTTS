package com.phonetts.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashLogFormatterTest {
    @Test
    fun taggedWithTheCrashSourceSoTheHelpScreenCanLabelIt() {
        val entry = CrashLogFormatter.format(atMs = 42L, threadName = "main", throwable = IllegalStateException("boom"))

        assertEquals(CrashLogFormatter.SOURCE, entry.source)
        assertEquals(42L, entry.atMs)
    }

    @Test
    fun messageNamesTheExceptionClassMessageAndThread() {
        val entry = CrashLogFormatter.format(atMs = 1L, threadName = "main", throwable = IllegalStateException("boom"))

        assertTrue(entry.message.contains("IllegalStateException"), entry.message)
        assertTrue(entry.message.contains("boom"), entry.message)
        assertTrue(entry.message.contains("main"), entry.message)
    }

    @Test
    fun missingExceptionMessageFallsBackToAPlaceholderRatherThanTheLiteralNull() {
        val entry = CrashLogFormatter.format(atMs = 1L, threadName = "t", throwable = RuntimeException())

        assertFalse(entry.message.contains("null"), entry.message)
    }

    @Test
    fun stackFramesAreIncludedUpToTheBoundWithAnOmittedCountAfterThat() {
        val throwable = RuntimeException("many frames")
        throwable.stackTrace =
            Array(CrashLogFormatter.MAX_STACK_FRAMES + 5) { i ->
                StackTraceElement("com.example.Class$i", "method$i", "Class$i.kt", i)
            }

        val entry = CrashLogFormatter.format(atMs = 1L, threadName = "main", throwable = throwable)

        assertTrue(entry.message.contains("Class0"), "keeps the top frame: ${entry.message}")
        assertTrue(entry.message.contains("(+5 more)"), "summarises the omitted tail: ${entry.message}")
        assertFalse(entry.message.contains("Class${CrashLogFormatter.MAX_STACK_FRAMES + 4}"), entry.message)
    }

    @Test
    fun emptyStackTraceProducesNoDanglingSeparator() {
        val throwable = RuntimeException("no frames")
        throwable.stackTrace = emptyArray()

        val entry = CrashLogFormatter.format(atMs = 1L, threadName = "main", throwable = throwable)

        assertFalse(entry.message.trimEnd().endsWith("|"), entry.message)
    }

    @Test
    fun theFormattedMessageIsAlwaysSingleLineSafe() {
        val throwable = IllegalStateException("first line\nsecond line\r\nthird line")

        val entry = CrashLogFormatter.format(atMs = 1L, threadName = "main", throwable = throwable)

        assertFalse(entry.message.contains('\n'), entry.message)
        assertFalse(entry.message.contains('\r'), entry.message)
    }

    @Test
    fun theFormattedMessageIsBoundedInLength() {
        val throwable = IllegalStateException("x".repeat(CrashLogFormatter.MAX_MESSAGE_LENGTH * 4))

        val entry = CrashLogFormatter.format(atMs = 1L, threadName = "main", throwable = throwable)

        assertTrue(entry.message.length <= CrashLogFormatter.MAX_MESSAGE_LENGTH, entry.message.length.toString())
    }
}
