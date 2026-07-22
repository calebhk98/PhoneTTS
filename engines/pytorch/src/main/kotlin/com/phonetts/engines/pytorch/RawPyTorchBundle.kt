package com.phonetts.engines.pytorch

/**
 * A pure, dependency-free detector for "does this bundle's file listing look like a raw PyTorch
 * checkpoint export" — a weights file (`.pth`/`.ckpt`/`.bin`) that ships with a `config.json` and
 * none of the packaging (`.onnx`) an actually-runnable engine in this repo would expect.
 *
 * This is deliberately NOT a claim of runnability (see [com.phonetts.engines.pytorch.PyTorchEngine]'s
 * kdoc and `engines/pytorch/INTEGRATION.md` for why raw PyTorch has no on-device runtime here) — it
 * only recognizes the *shape*, so the app can eventually surface a clear, honest message ("this
 * looks like a raw PyTorch checkpoint, which PhoneTTS cannot run on-device — convert it to
 * ONNX/ExecuTorch first") instead of a bare "no engine recognized this model" (the same spirit as
 * [com.phonetts.core.resolver.DetectionFailureExplainer]). Nothing in this repo currently wires
 * that message; the function exists so a future call site (the resolver's failure path, a Browse
 * screen hint, …) has a single, tested, honest place to ask the question — see INTEGRATION.md.
 *
 * Takes a plain `Set<String>` of file names, not a [com.phonetts.core.model.ModelBundle], so it
 * stays trivially unit-testable and reusable from anywhere (a resolver-side check does not need to
 * construct a full bundle just to ask this question).
 */
object RawPyTorchBundle {
    private val WEIGHT_EXTENSIONS = listOf(".pth", ".ckpt", ".bin")
    private const val CONFIG_FILE_NAME = "config.json"
    private const val ONNX_EXTENSION = ".onnx"

    /**
     * True if [fileNames] contains at least one recognized PyTorch weight extension plus a
     * `config.json`, and no `.onnx` file (an ONNX-packaged bundle belongs to a different, actually
     * runnable, engine — never double-claimed here even just as a "looks like" signal).
     */
    fun looksLikeRawPyTorch(fileNames: Set<String>): Boolean {
        val hasWeightFile = WEIGHT_EXTENSIONS.any { ext -> fileNames.any { it.endsWith(ext) } }
        val hasConfig = CONFIG_FILE_NAME in fileNames
        val hasOnnx = fileNames.any { it.endsWith(ONNX_EXTENSION) }
        return hasWeightFile && hasConfig && !hasOnnx
    }
}
