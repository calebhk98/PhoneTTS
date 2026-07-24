package com.phonetts.engines.supertonic

import com.phonetts.engines.common.json.JsonValue
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asArrayOrNull
import com.phonetts.engines.common.json.asFloatOrNull
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull

/**
 * One Supertonic voice style: two independently-shaped tensors read straight from a
 * `voice_styles/<name>.json` file - `style_ttl` (feeds `text_encoder.onnx`/`vector_estimator.onnx`)
 * and `style_dp` (feeds `duration_predictor.onnx`). Every real voice style file (`Supertone/
 * supertonic-3` ships `M1`-`M5`/`F1`-`F5`) carries both tensors as `{"dims":[...],"data":[...]}`.
 *
 * VALIDATED (docs/research/supertonic-facts.md): downloaded `voice_styles/M1.json` directly from
 * `Supertone/supertonic-3` (2026-07-24) - `style_ttl.dims == [1, 50, 256]`,
 * `style_dp.dims == [1, 8, 16]` (12800 + 128 = 12928 floats total, which independently matches the
 * 51712-byte per-voice `.bin` files - `12928 * 4 bytes` - in the `onnx-community/
 * Supertonic-TTS-ONNX` re-export). Both shapes are READ from each file's own `dims` field here,
 * never hardcoded (CLAUDE.md rule 1) - the numbers above are only the validated shape this parser
 * was checked against, not a literal this class relies on. Cross-checked against
 * `supertonic-py/supertonic/loader.py`'s `_load_style_from_json`
 * (`np.array(data, dtype=np.float32).reshape(*dims)`) and the official Java
 * `Helper.loadVoiceStyle`/`VoiceStyleData`, both of which read this exact `{dims, data}` shape.
 *
 * [data]'s nested arrays are read in row-major (outer-to-inner) order via [collectFloats], matching
 * both `numpy.reshape`'s default C-order and the ONNX row-major tensor convention.
 */
internal class SupertonicStyle(
    val ttl: FloatArray,
    val ttlShape: IntArray,
    val dp: FloatArray,
    val dpShape: IntArray,
) {
    companion object {
        private const val KEY_STYLE_TTL = "style_ttl"
        private const val KEY_STYLE_DP = "style_dp"
        private const val KEY_DIMS = "dims"
        private const val KEY_DATA = "data"

        /** Parses [json], or null if malformed / missing either tensor (fail closed). */
        fun parse(json: String): SupertonicStyle? {
            val root = MiniJson.parse(json)?.asObjectOrNull() ?: return null
            val ttl = parseTensor(root[KEY_STYLE_TTL]) ?: return null
            val dp = parseTensor(root[KEY_STYLE_DP]) ?: return null
            return SupertonicStyle(ttl.first, ttl.second, dp.first, dp.second)
        }

        private fun parseTensor(node: JsonValue?): Pair<FloatArray, IntArray>? {
            val obj = node?.asObjectOrNull() ?: return null
            val dims = parseDims(obj[KEY_DIMS]) ?: return null
            val flat = mutableListOf<Float>()
            if (!collectFloats(obj[KEY_DATA], flat)) return null
            return flat.toFloatArray() to dims
        }

        private fun parseDims(node: JsonValue?): IntArray? {
            val values = node?.asArrayOrNull() ?: return null
            val dims = IntArray(values.size)
            for (i in values.indices) {
                dims[i] = values[i].asIntOrNull() ?: return null
            }
            return dims
        }

        /** Recursively walks arbitrarily-nested JSON number arrays, appending every leaf in order. */
        private fun collectFloats(
            node: JsonValue?,
            out: MutableList<Float>,
        ): Boolean {
            val array = node?.asArrayOrNull()
            if (array != null) return array.all { collectFloats(it, out) }
            val value = node?.asFloatOrNull() ?: return false
            out.add(value)
            return true
        }
    }
}
