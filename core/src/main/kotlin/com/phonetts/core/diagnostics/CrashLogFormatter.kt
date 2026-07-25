package com.phonetts.core.diagnostics

import com.phonetts.core.store.ErrorLogEntry

/**
 * Turns an uncaught [Throwable] into a durable, single-line-safe [ErrorLogEntry] (the crash-logging
 * half of "no error log saved" - the app-wide crash handler lives in `:app`'s
 * `com.phonetts.app.diagnostics.CrashReporter`, which is the only caller of [format]).
 *
 * Deliberately pure and deterministic given its inputs, matching every other `:core` seam: [atMs] and
 * [threadName] are caller-supplied (this never reads the wall clock or `Thread.currentThread()`
 * itself), and everything else comes straight off the [Throwable] via plain `java.lang` calls
 * available on any JVM - no Android dependency, so this is unit-testable without a device/emulator.
 *
 * Bounded on two axes so one pathological throwable can never make a single crash entry dominate or
 * corrupt the durable store: at most [MAX_STACK_FRAMES] stack frames are kept (a `StackOverflowError`
 * can carry thousands), and the whole message is capped at [MAX_MESSAGE_LENGTH] characters. Embedded
 * newlines (from the exception's own message, not unusual for e.g. multi-line native crash text) are
 * flattened to spaces so the result is always safe to render as one line in the Help screen's error
 * log list and to copy verbatim from there.
 */
object CrashLogFormatter {
    /** The [ErrorLogEntry.source] every crash entry is tagged with, so the Help screen can label it. */
    const val SOURCE = "crash"

    /** Stack frames kept from the top of the trace; anything past this is summarised as "(+N more)". */
    const val MAX_STACK_FRAMES = 12

    /** Hard cap on the formatted message length (characters), applied after it is single-lined. */
    const val MAX_MESSAGE_LENGTH = 2000

    /** Builds the durable entry for an uncaught [throwable] on [threadName], recorded at [atMs]. */
    fun format(
        atMs: Long,
        threadName: String,
        throwable: Throwable,
    ): ErrorLogEntry = ErrorLogEntry(atMs = atMs, source = SOURCE, message = renderMessage(threadName, throwable))

    private fun renderMessage(
        threadName: String,
        throwable: Throwable,
    ): String {
        val header = "${throwable.javaClass.name}: ${throwable.message ?: "(no message)"} on thread '$threadName'"
        val stack = throwable.stackTrace ?: emptyArray()
        val kept = stack.take(MAX_STACK_FRAMES).joinToString(" | ") { "at $it" }
        val omitted = (stack.size - MAX_STACK_FRAMES).coerceAtLeast(0)
        val framesPart =
            when {
                kept.isEmpty() -> ""
                omitted > 0 -> " | $kept | (+$omitted more)"
                else -> " | $kept"
            }
        return "$header$framesPart"
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_MESSAGE_LENGTH)
    }
}
