package com.phonetts.engines.inflect

import com.phonetts.core.model.ModelBundle

// Shared fixtures for the Inflect seam tests. The config bodies are trimmed-down but STRUCTURALLY
// faithful copies of the real published files (owensong/Inflect-{Micro,Nano}-v2-ONNX/config.json,
// read at authoring time): same "format" marker, same nesting, same field names and values for
// every field the engine actually reads.
internal object InflectFixtures {
    const val MICRO_CONFIG =
        """
        {
          "format": "inflect_v2_inference_config_v1",
          "data": { "sampling_rate": 24000, "add_blank": true, "n_speakers": 0 },
          "model": { "inter_channels": 192, "hidden_channels": 96, "filter_channels": 768 }
        }
        """

    const val NANO_CONFIG =
        """
        {
          "format": "inflect_v2_inference_config_v1",
          "data": { "sampling_rate": 24000, "add_blank": true, "n_speakers": 0 },
          "model": { "inter_channels": 128, "hidden_channels": 72, "filter_channels": 384 }
        }
        """

    /** A genuine Inflect config for a size this engine has never seen (widths matching neither). */
    const val FUTURE_SIZE_CONFIG =
        """
        {
          "format": "inflect_v2_inference_config_v1",
          "data": { "sampling_rate": 22050 },
          "model": { "inter_channels": 256, "hidden_channels": 128 }
        }
        """

    /** Parses as JSON and looks VITS-ish, but is not an Inflect export - inspect() must refuse it. */
    const val FOREIGN_CONFIG =
        """
        {
          "model_type": "vits",
          "data": { "sampling_rate": 24000 },
          "model": { "inter_channels": 192, "hidden_channels": 96 }
        }
        """

    const val ROOT = "/models/inflect"

    /** The flat folder the in-app curated download produces (each file under its local name). */
    fun flatBundle(
        config: String = MICRO_CONFIG,
        id: String = "inflect-micro-v2",
    ): ModelBundle =
        ModelBundle(
            id = id,
            fileNames = setOf(InflectEngine.DURATION_FILE, InflectEngine.DECODE_FILE, InflectEngine.CONFIG_FILE),
            sideFiles = mapOf(InflectEngine.CONFIG_FILE to config),
            rootPath = ROOT,
        )

    /** The published repo layout a `git clone` sideload produces (graphs under `onnx/`). */
    fun repoLayoutBundle(config: String = MICRO_CONFIG): ModelBundle =
        ModelBundle(
            id = "Inflect-Micro-v2-ONNX",
            fileNames =
                setOf(
                    "onnx/${InflectEngine.DURATION_FILE}",
                    "onnx/${InflectEngine.DECODE_FILE}",
                    "onnx/README.md",
                    InflectEngine.CONFIG_FILE,
                ),
            sideFiles = mapOf(InflectEngine.CONFIG_FILE to config),
            rootPath = ROOT,
        )
}
