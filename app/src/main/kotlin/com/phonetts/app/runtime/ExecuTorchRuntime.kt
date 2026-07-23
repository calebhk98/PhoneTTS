package com.phonetts.app.runtime

import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Runtime
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.runtime.Tensor
import org.pytorch.executorch.DType
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor as EtTensor

/**
 * The concrete ExecuTorch Runtime backend (`org.pytorch:executorch-android`, the PyTorch Edge
 * on-device runtime for `.pte` graphs), registered under the id [ID]. Mirrors [OnnxRuntime]'s
 * shape exactly: it adapts the app's runtime-agnostic [Tensor] to and from ExecuTorch's
 * [EtTensor]/[EValue] at the edge, so an engine never sees an ExecuTorch type.
 *
 * VERIFIED (Maven Central metadata, `repo1.maven.org/maven2/org/pytorch/executorch-android/
 * maven-metadata.xml`, and the published artifact directory): the coordinate is
 * `org.pytorch:executorch-android`, latest release `1.3.1` - a ~7 MB `.aar`, BSD-3-Clause,
 * bundling `libexecutorch.so` for `arm64-v8a` + `x86_64` plus the XNNPACK CPU backend, with
 * transitive runtime deps on `com.facebook.fbjni:fbjni:0.7.0` and
 * `com.facebook.soloader:nativeloader:0.10.5` (Gradle resolves these automatically from the AAR's
 * published `.module` metadata - see `engines/executorch/INTEGRATION.md`).
 *
 * VERIFIED (`extension/android/executorch_android/src/main/java/org/pytorch/executorch/
 * {Module,Tensor,EValue,DType}.java` at tag `v1.3.1`, `raw.githubusercontent.com/pytorch/
 * executorch`): `Module.load(path, loadMode, numThreads)` loads a `.pte`; `Module.execute
 * (methodName, EValue...)` runs a named graph method BY POSITION (`forward` is the common case,
 * but a bounded-dynamic-shape export can expose extra methods like `forward_128` - see
 * [MODULE_METHOD_EXTRA]); `Tensor.fromBlob(data, shape)` / `Tensor.getDataAs*Array()` cover
 * `float[]`/`long[]`/`int[]` (among others); `EValue.from(Tensor)` / `EValue.toTensor()` wrap and
 * unwrap.
 *
 * KNOWN GAP (flagged, not silently papered over): the public Java `Tensor` API exposes factories
 * for byte/short/int/long/half/float/double, but **not `bool`**, even though [DType.BOOL] exists
 * as an output tag. A `.pte` graph with a `bool`-typed input (e.g. Kokoro-on-ExecuTorch's
 * `text_mask` - see `com.phonetts.engines.executorch.ExecuTorchKokoroEngine`) cannot be fed a true
 * bool tensor through this bridge; such an input is encoded as INT64 instead, UNVALIDATED against
 * the real graph on a device. See `engines/executorch/INTEGRATION.md`.
 *
 * NOTE (matching [OnnxRuntime]'s own caveat): this compiles and will run real ExecuTorch graphs
 * once the AAR dependency is added, but every engine's ASSUMED input/output tensor names, shapes,
 * and method names must still be validated against the real exported `.pte` files before audio is
 * correct.
 */
class ExecuTorchRuntime : Runtime {
    override val id: String = ID

    /**
     * Always-on (ExecuTorch ships in the normal APK, unlike the opt-in native-NDK bridges), but
     * fails safe rather than crashing the app if `libexecutorch.so` isn't actually present/loadable
     * on this device/build. Forces [Module]'s static initializer (which calls
     * `NativeLoader.loadLibrary("executorch")`) to run via an explicit, initializing class load, so
     * any resulting [LinkageError]/`UnsatisfiedLinkError` is caught here instead of surfacing as a
     * crash the first time an engine tries to use this runtime.
     */
    override fun isAvailable(): Boolean =
        runCatching { Class.forName(Module::class.java.name, true, javaClass.classLoader) }.isSuccess

    override fun createSession(
        modelPath: String,
        options: RuntimeOptions,
    ): InferenceSession {
        val module = Module.load(modelPath, Module.LOAD_MODE_FILE, options.intraOpThreads)
        val methodName = options.extras[MODULE_METHOD_EXTRA] ?: DEFAULT_METHOD
        return ExecuTorchInferenceSession(module, methodName)
    }

    companion object {
        const val ID = "executorch"

        /**
         * [RuntimeOptions.extras] key an engine sets to run a `.pte` method other than the default
         * `forward` (e.g. a bounded-dynamic-shape export's `forward_128`). A cross-module string
         * contract - this module (`:app`) cannot depend on an engine module's constant, nor can an
         * engine module depend back on `:app` - so both sides hardcode the literal `"method"` and
         * must be kept in sync; see `engines/executorch/INTEGRATION.md`.
         */
        const val MODULE_METHOD_EXTRA = "method"
        const val DEFAULT_METHOD = "forward"
    }
}

private class ExecuTorchInferenceSession(
    private val module: Module,
    private val methodName: String,
) : InferenceSession {
    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        // Positional, not named: ExecuTorch's Module.execute(method, EValue...) binds arguments by
        // POSITION to the graph's forward() signature, unlike ONNX Runtime's name-keyed
        // session.run. The caller (an engine) is responsible for inserting `inputs` in the exact
        // order its model's forward() expects -- Kotlin's mapOf()/linkedMapOf() already preserve
        // insertion order, which is what makes that order explicit and testable at the call site.
        val evalues = inputs.values.map { EValue.from(toExecuTorch(it)) }.toTypedArray()
        val results = module.execute(methodName, *evalues)
        val outputs = LinkedHashMap<String, Tensor>()
        results.forEachIndexed { index, value -> outputs["output$index"] = fromExecuTorch(value.toTensor()) }
        return outputs
    }

    override fun close() = module.close()

    private fun toExecuTorch(tensor: Tensor): EtTensor {
        val shape = LongArray(tensor.shape.size) { tensor.shape[it].toLong() }
        // A [Tensor] holds either floats or longs; asFloats() throws for a long tensor.
        return runCatching { EtTensor.fromBlob(tensor.asFloats(), shape) }
            .getOrElse { EtTensor.fromBlob(tensor.asLongs(), shape) }
    }

    private fun fromExecuTorch(tensor: EtTensor): Tensor {
        val shape = IntArray(tensor.shape().size) { tensor.shape()[it].toInt() }
        return when (tensor.dtype()) {
            DType.FLOAT -> Tensor.floats(tensor.getDataAsFloatArray(), shape)
            DType.INT64 -> Tensor.longs(tensor.getDataAsLongArray(), shape)
            // INT32 is common for ExecuTorch duration/index/count outputs; widen to the seam's
            // long representation rather than erroring (:core's Tensor models only float/long).
            DType.INT32 -> Tensor.longs(widenToLongs(tensor.getDataAsIntArray()), shape)
            else -> error("unsupported ExecuTorch output dtype ${tensor.dtype()}")
        }
    }

    private fun widenToLongs(data: IntArray): LongArray = LongArray(data.size) { data[it].toLong() }
}
