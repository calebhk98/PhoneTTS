package com.phonetts.engines.common

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Runtime

// Leaf helpers for the session-lifecycle boilerplate every engine's load()/unload() repeats.
// All parameterized by caller-supplied ids/keys/labels — they encode no model fact themselves.

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

/** Close every non-null session, ignoring individual close failures (best-effort cleanup). */
fun closeAllQuietly(sessions: Iterable<InferenceSession?>) {
    for (session in sessions) {
        session ?: continue
        runCatching { session.close() }
    }
}

/** Vararg convenience for the fixed-session engines; N-session engines pass a collection directly. */
fun closeAllQuietly(vararg sessions: InferenceSession?) = closeAllQuietly(sessions.asList())
