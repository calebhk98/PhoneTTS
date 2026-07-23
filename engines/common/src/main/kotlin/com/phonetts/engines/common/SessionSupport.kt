package com.phonetts.engines.common

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Runtime

// Leaf helpers for the session-lifecycle boilerplate every engine's load()/unload() repeats.
// All parameterized by caller-supplied ids/keys/labels - they encode no model fact themselves.

/** The runtime registered under [runtimeId], or a clear failure naming [engineLabel]. */
fun requireRuntime(
    context: EngineContext,
    runtimeId: String,
    engineLabel: String,
): Runtime =
    context.runtimes.get(runtimeId)
        ?: error("$engineLabel requires the '$runtimeId' runtime, but none is registered")

/** The on-device path for [assetKey], or a clear failure naming [engineLabel]. */
fun requireAssetPath(
    descriptor: ModelDescriptor,
    assetKey: String,
    engineLabel: String,
): String =
    descriptor.assetPaths[assetKey]
        ?: error("$engineLabel descriptor '${descriptor.modelId}' is missing its '$assetKey' asset path")

/**
 * Open several sessions with all-or-nothing cleanup: the block adds each session it creates to
 * [opened] as it goes; if any `createSession` throws part-way, every already-opened session is
 * closed before the failure propagates - so a partial `load()` never leaks native sessions (which
 * matters on a 4 GB device where a corrupt/oversized model fails mid-load).
 */
@Suppress("TooGenericExceptionCaught") // rolling back on ANY failure is the whole point
inline fun <T> openWithRollback(block: (opened: MutableList<InferenceSession>) -> T): T {
    val opened = mutableListOf<InferenceSession>()
    try {
        return block(opened)
    } catch (failure: Throwable) {
        closeAllQuietly(opened)
        throw failure
    }
}

/** Close every non-null session, ignoring individual close failures (best-effort cleanup). */
fun closeAllQuietly(sessions: Iterable<InferenceSession?>) {
    for (session in sessions) {
        session ?: continue
        runCatching { session.close() }
    }
}

/** Vararg convenience for the fixed-session engines; N-session engines pass a collection directly. */
fun closeAllQuietly(vararg sessions: InferenceSession?) = closeAllQuietly(sessions.asList())
