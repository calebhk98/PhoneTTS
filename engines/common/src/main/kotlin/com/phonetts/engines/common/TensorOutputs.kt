package com.phonetts.engines.common

import com.phonetts.core.runtime.Tensor

/**
 * Pull a named output tensor out of an inference result, or fail with a message naming the engine
 * and the missing key. Every engine repeated this same guard after `session.run(...)`.
 */
fun Map<String, Tensor>.tensorOrError(
    key: String,
    engineLabel: String,
): Tensor = this[key] ?: error("$engineLabel: session did not return a '$key' output tensor")

fun Map<String, Tensor>.floatsOrError(
    key: String,
    engineLabel: String,
): FloatArray = tensorOrError(key, engineLabel).asFloats()
