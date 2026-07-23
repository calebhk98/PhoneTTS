package com.phonetts.app.runtime

import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Runtime
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.runtime.Tensor
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Tensor as TfLiteTensor

/**
 * The LiteRT / TensorFlow-Lite (`.tflite`) inference backend (issue #109), registered under the id
 * `"litert"` that the :engines:litert engine asks for. It adapts the app's runtime-agnostic [Tensor]
 * to and from LiteRT's native tensors at the edge, exactly like [OnnxRuntime] does for ONNX, so
 * engines never see a LiteRT type.
 *
 * SCAFFOLD (issue #109): the GOAL of this first cut is NOT correct audio - it is RICH, COPYABLE
 * FAILURE LOGGING. Every path that can fail to load or run a `.tflite` writes a detailed diagnostic
 * (model path, the model's actual input/output tensor names + dtypes + shapes, and the exception
 * class + full cause chain) to the durable error log via [logDiagnostic] BEFORE rethrowing, so a real
 * device run tells us exactly what went wrong and what the model's I/O contract is. The interpreter is
 * JNI-backed, so it only actually runs on device/emulator (like [OnnxRuntime]); here it just compiles.
 *
 * We use the classic Java `org.tensorflow.lite.Interpreter` API (from `org.tensorflow:tensorflow-lite`)
 * rather than the newer Kotlin `CompiledModel` API: the latter (litert 2.x) ships Kotlin metadata
 * newer than this project's compiler can read, and the Java API also exposes the per-tensor
 * name/shape/dtype introspection this scaffold needs to log a model's contract.
 *
 * [logDiagnostic] is supplied by `AppGraph` as a lambda that appends to the durable error log with a
 * `System.currentTimeMillis()` timestamp and the `"litert"` source tag - this class never reads the
 * clock itself (that stays at the app edge, matching every other seam).
 */
class LiteRtRuntime(
    private val logDiagnostic: (String) -> Unit,
) : Runtime {
    override val id: String = RUNTIME_ID

    // Class-presence check: true if the tensorflow-lite AAR is on the classpath. The native library
    // loads lazily when an Interpreter is constructed on-device, so a build that ships the AAR reports
    // available and any load failure surfaces a logged diagnostic rather than silently disappearing.
    override fun isAvailable(): Boolean = runCatching { Class.forName("org.tensorflow.lite.Interpreter") }.isSuccess

    override fun createSession(
        modelPath: String,
        options: RuntimeOptions,
    ): InferenceSession {
        val interpreter = createInterpreter(modelPath, options)
        // The useful artifact for iterating on this scaffold: the model's real I/O contract, written to
        // the durable log the moment it loads, so we can wire a proper engine for it.
        logDiagnostic(describeSignature(modelPath, interpreter))
        return LiteRtInferenceSession(modelPath, interpreter, logDiagnostic)
    }

    @Suppress("TooGenericExceptionCaught") // logging ANY load failure before rethrow is the whole point
    private fun createInterpreter(
        modelPath: String,
        options: RuntimeOptions,
    ): Interpreter {
        try {
            val interpreterOptions = Interpreter.Options().apply { setNumThreads(options.intraOpThreads) }
            return Interpreter(File(modelPath), interpreterOptions)
        } catch (failure: Throwable) {
            logDiagnostic(loadFailureMessage(modelPath, failure))
            throw RuntimeException("LiteRT failed to load '$modelPath' - see the copyable error log", failure)
        }
    }

    @Suppress("TooGenericExceptionCaught") // introspection is best-effort; a miss is logged, never fatal
    private fun describeSignature(
        modelPath: String,
        interpreter: Interpreter,
    ): String {
        val detail =
            runCatching { introspectSignature(interpreter) }
                .getOrElse { failure ->
                    "\n  (could not introspect I/O signature: ${failure.javaClass.name}: ${failure.message})"
                }
        return "LiteRT model loaded: $modelPath$detail"
    }

    private fun introspectSignature(interpreter: Interpreter): String {
        val builder = StringBuilder()
        for (index in 0 until interpreter.inputTensorCount) {
            builder.append(describeTensor("input", index, interpreter.getInputTensor(index)))
        }
        for (index in 0 until interpreter.outputTensorCount) {
            builder.append(describeTensor("output", index, interpreter.getOutputTensor(index)))
        }
        return builder.toString()
    }

    private fun describeTensor(
        role: String,
        index: Int,
        tensor: TfLiteTensor,
    ): String =
        "\n  $role[$index] ${tensor.name()} : ${tensor.dataType()} shape=${tensor.shape().joinToString("x")}"

    private companion object {
        const val RUNTIME_ID = "litert"
    }
}

/**
 * One loaded `.tflite` graph. All model-specific correctness is deferred (issue #109 scaffold); the
 * load-bearing behaviour here is that a failed [run] writes a rich, copyable diagnostic - the input
 * names + shapes it fed and the full exception cause chain - to the durable log before rethrowing, so
 * a native crash never escapes unexplained. Inputs/outputs are mapped by the graph's own tensor names,
 * so an engine keeps speaking the name-based [InferenceSession] contract.
 */
private class LiteRtInferenceSession(
    private val modelPath: String,
    private val interpreter: Interpreter,
    private val logDiagnostic: (String) -> Unit,
) : InferenceSession {
    @Suppress("TooGenericExceptionCaught") // a native run crash MUST be logged before it escapes
    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        try {
            val orderedInputs = buildInputs(inputs)
            val outputBuffers = allocateOutputs()
            interpreter.runForMultipleInputsOutputs(orderedInputs, outputBuffers.byIndex)
            return readOutputs(outputBuffers.byName)
        } catch (failure: Throwable) {
            logDiagnostic(runFailureMessage(modelPath, inputs, failure))
            throw RuntimeException("LiteRT run failed for '$modelPath' - see the copyable error log", failure)
        }
    }

    // Feed each input by matching the graph's tensor name to the caller's map, in the graph's index
    // order (the Java Interpreter is index-based). A missing input is a clear, logged failure.
    private fun buildInputs(inputs: Map<String, Tensor>): Array<Any> {
        val ordered = arrayOfNulls<Any>(interpreter.inputTensorCount)
        for (index in 0 until interpreter.inputTensorCount) {
            val tfTensor = interpreter.getInputTensor(index)
            val supplied = inputs[tfTensor.name()] ?: error("LiteRT: no input supplied for '${tfTensor.name()}'")
            ordered[index] = encodeInput(tfTensor, supplied)
        }
        @Suppress("UNCHECKED_CAST")
        return ordered as Array<Any>
    }

    private fun allocateOutputs(): OutputBuffers {
        val byIndex = HashMap<Int, Any>()
        val byName = LinkedHashMap<String, DecodableBuffer>()
        for (index in 0 until interpreter.outputTensorCount) {
            val tfTensor = interpreter.getOutputTensor(index)
            val buffer = ByteBuffer.allocateDirect(tfTensor.numBytes()).order(ByteOrder.nativeOrder())
            byIndex[index] = buffer
            byName[tfTensor.name()] = DecodableBuffer(buffer, tfTensor.dataType(), tfTensor.numElements())
        }
        return OutputBuffers(byIndex, byName)
    }

    override fun close() {
        runCatching { interpreter.close() }
    }
}

private class OutputBuffers(
    val byIndex: Map<Int, Any>,
    val byName: Map<String, DecodableBuffer>,
)

private class DecodableBuffer(
    val buffer: ByteBuffer,
    val dataType: DataType,
    val elements: Int,
)

// How deep to walk an exception's cause chain when building a diagnostic - enough to reach the native
// root cause without unbounded output.
private const val MAX_CAUSE_DEPTH = 8

// Encode the caller's [Tensor] into a native-order direct buffer matching the graph tensor's dtype.
// A [Tensor] holds either floats or longs; we pick the write by the graph's expected dtype so an
// int64 id tensor and a float scalar both land correctly. Unknown dtypes fall back to a float write.
private fun encodeInput(
    tfTensor: TfLiteTensor,
    tensor: Tensor,
): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(tfTensor.numBytes()).order(ByteOrder.nativeOrder())
    when (tfTensor.dataType()) {
        DataType.INT64 -> tensor.asLongs().forEach { buffer.putLong(it) }
        DataType.INT32 -> tensor.asLongs().forEach { buffer.putInt(it.toInt()) }
        else -> tensor.asFloats().forEach { buffer.putFloat(it) }
    }
    buffer.rewind()
    return buffer
}

// Decode each output buffer back into a flat [Tensor] by its dtype. Shape is the flat length: a
// scaffold value, not the model's true rank (issue #109 defers correct audio).
private fun readOutputs(buffers: Map<String, DecodableBuffer>): Map<String, Tensor> {
    val outputs = LinkedHashMap<String, Tensor>()
    for ((name, decodable) in buffers) {
        val buffer = decodable.buffer.also { it.rewind() }
        outputs[name] =
            when (decodable.dataType) {
                DataType.INT64 -> Tensor.longs(LongArray(decodable.elements) { buffer.long })
                else -> Tensor.floats(FloatArray(decodable.elements) { buffer.float })
            }
    }
    return outputs
}

private fun loadFailureMessage(
    modelPath: String,
    failure: Throwable,
): String =
    buildString {
        append("LiteRT FAILED to load model\n")
        append("  path: ").append(modelPath).append('\n')
        append(describeThrowable(failure))
    }

private fun runFailureMessage(
    modelPath: String,
    inputs: Map<String, Tensor>,
    failure: Throwable,
): String =
    buildString {
        append("LiteRT FAILED to run model\n")
        append("  path: ").append(modelPath).append('\n')
        append("  inputs fed:\n")
        inputs.forEach { (name, tensor) ->
            append("    ").append(name).append(" shape=").append(tensor.shape.joinToString("x")).append('\n')
        }
        append(describeThrowable(failure))
    }

// The exception class + message plus its cause chain - the copyable "why" the user asked for.
private fun describeThrowable(failure: Throwable): String =
    buildString {
        append("  exception: ").append(failure.javaClass.name).append(": ").append(failure.message)
        var cause = failure.cause
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            append("\n  caused by: ").append(cause.javaClass.name).append(": ").append(cause.message)
            cause = cause.cause
            depth++
        }
    }
