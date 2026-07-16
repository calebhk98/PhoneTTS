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

/**
 * Pull the SOLE output tensor out of an inference result, regardless of its name. Some ONNX
 * exports auto-number their output (e.g. MeloTTS's acoustic graph — see docs/research/onnx-io.md)
 * so no fixed name can be hardcoded; a graph with exactly one output can still be read
 * positionally. [run] already hands back whatever key the runtime reported, so this needs no
 * change to [com.phonetts.core.runtime.InferenceSession] — just a different way to pull a value
 * back out of the map it already returns.
 */
fun Map<String, Tensor>.singleTensorOrError(engineLabel: String): Tensor {
    val entry = entries.singleOrNull()
    return checkNotNull(entry) {
        "$engineLabel: session returned $size output tensor(s), expected exactly 1 to read positionally"
    }.value
}

fun Map<String, Tensor>.singleFloatsOrError(engineLabel: String): FloatArray =
    singleTensorOrError(engineLabel).asFloats()
