package com.phonetts.integration

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Runtime
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.runtime.Tensor
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The JVM twin of the app's `com.phonetts.app.runtime.OnnxRuntime`: the exact same
 * [com.phonetts.core.runtime.Runtime] seam, backed by desktop `com.microsoft.onnxruntime:onnxruntime`
 * instead of `onnxruntime-android`. Kept byte-for-byte equivalent so the end-to-end auto-load test
 * runs the REAL engine code — only this platform adapter differs from what ships on-device.
 */
class JvmOnnxRuntime : Runtime {
    override val id: String = "onnx"

    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    override fun isAvailable(): Boolean = runCatching { environment }.isSuccess

    override fun createSession(
        modelPath: String,
        options: RuntimeOptions,
    ): InferenceSession {
        val sessionOptions = OrtSession.SessionOptions().apply { setIntraOpNumThreads(options.intraOpThreads) }
        return JvmOnnxSession(environment, environment.createSession(modelPath, sessionOptions))
    }
}

private class JvmOnnxSession(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : InferenceSession {
    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val onnxInputs = inputs.mapValues { (_, tensor) -> toOnnx(tensor) }
        try {
            session.run(onnxInputs).use { result ->
                val outputs = LinkedHashMap<String, Tensor>()
                for (entry in result) outputs[entry.key] = fromOnnx(entry.value)
                return outputs
            }
        } finally {
            onnxInputs.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    private fun toOnnx(tensor: Tensor): OnnxTensor {
        val shape = LongArray(tensor.shape.size) { tensor.shape[it].toLong() }
        return runCatching { OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor.asFloats()), shape) }
            .getOrElse { OnnxTensor.createTensor(environment, LongBuffer.wrap(tensor.asLongs()), shape) }
    }

    private fun fromOnnx(value: OnnxValue): Tensor {
        val onnx = value as OnnxTensor
        val shape = IntArray(onnx.info.shape.size) { onnx.info.shape[it].toInt() }
        return when (onnx.info.type) {
            OnnxJavaType.FLOAT -> Tensor.floats(readFloats(onnx.floatBuffer), shape)
            OnnxJavaType.INT64 -> Tensor.longs(readLongs(onnx.longBuffer), shape)
            else -> error("unsupported ONNX output type ${onnx.info.type}")
        }
    }

    private fun readFloats(buffer: FloatBuffer): FloatArray = FloatArray(buffer.remaining()).also { buffer.get(it) }

    private fun readLongs(buffer: LongBuffer): LongArray = LongArray(buffer.remaining()).also { buffer.get(it) }
}
