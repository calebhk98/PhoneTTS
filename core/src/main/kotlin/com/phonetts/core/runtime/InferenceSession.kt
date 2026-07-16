package com.phonetts.core.runtime

/**
 * One loaded model graph, ready to run. Named-tensor in, named-tensor out — the lowest common
 * denominator across ONNX and an LLM-style backend. An engine that needs several graphs
 * (e.g. a BERT prosody step plus an acoustic model) simply holds several sessions; an
 * autoregressive engine calls [run] in a loop. The seam has no idea which.
 */
interface InferenceSession : AutoCloseable {
    fun run(inputs: Map<String, Tensor>): Map<String, Tensor>

    override fun close()
}
