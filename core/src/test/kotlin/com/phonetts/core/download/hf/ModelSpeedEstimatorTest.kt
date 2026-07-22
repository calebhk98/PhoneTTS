package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelSpeedEstimatorTest {
    @Test
    fun estimatesParamsAtTheDefaultFp16RateWhenNoPrecisionHintIsPresent() {
        // 200 MB / 2 bytes-per-param (fp16 default) = 100M params.
        val params = ParameterCountEstimator.estimate(totalBytes = 200_000_000L, precisionHints = listOf("model.onnx"))

        assertEquals(100_000_000L, params)
    }

    @Test
    fun recognizesAnInt8PrecisionTokenAndHalvesTheImpliedParamCountVsFp16() {
        val fp16Params = ParameterCountEstimator.estimate(100_000_000L, listOf("model_fp16.onnx"))
        val int8Params = ParameterCountEstimator.estimate(100_000_000L, listOf("model_int8.onnx"))

        val message = "the same byte count should imply MORE params at a smaller bytes-per-param precision"
        assertTrue(int8Params > fp16Params, message)
        assertEquals(fp16Params * 2, int8Params)
    }

    @Test
    fun recognizesAnInt4TokenAsQuarterBytesPerParamOfFp32() {
        val fp32Params = ParameterCountEstimator.estimate(100_000_000L, listOf("weights.f32.bin"))
        val int4Params = ParameterCountEstimator.estimate(100_000_000L, listOf("weights.q4.bin"))

        assertEquals(fp32Params * 8, int4Params)
    }

    @Test
    fun zeroOrNegativeSizeYieldsZeroParamsRatherThanNegativeOrCrashing() {
        assertEquals(0L, ParameterCountEstimator.estimate(0L))
        assertEquals(0L, ParameterCountEstimator.estimate(-5L))
    }

    @Test
    fun speedPredictionIsHigherForFewerParameters() {
        val small = SpeedPredictor.predictRealtimeMultiple(10_000_000L)
        val large = SpeedPredictor.predictRealtimeMultiple(300_000_000L)

        assertTrue(small > large, "a smaller model should be predicted faster than a bigger one")
    }

    @Test
    fun speedPredictionAtTheReferencePointMatchesTheReferenceMultiple() {
        val atReference = SpeedPredictor.predictRealtimeMultiple(30_000_000L)

        assertApproxEquals(8.0, atReference)
    }

    @Test
    fun speedPredictionIsAlwaysPositiveEvenForATinyOrZeroParamCount() {
        assertTrue(SpeedPredictor.predictRealtimeMultiple(0L) > 0.0)
        assertTrue(SpeedPredictor.predictRealtimeMultiple(1L) > 0.0)
    }

    @Test
    fun modelSpeedEstimateBundlesBothNumbersFromOneTotalBytesCall() {
        val estimate = ModelSpeedEstimate.from(totalBytes = 60_000_000L, precisionHints = listOf("model_fp16.onnx"))

        assertEquals(30_000_000L, estimate.paramCount)
        assertApproxEquals(8.0, estimate.realtimeMultiple)
    }
}

// A small tolerance-based double comparison, kept local rather than relying on a specific
// kotlin.test artifact's overload set (varies by target/version).
private fun assertApproxEquals(
    expected: Double,
    actual: Double,
    absoluteTolerance: Double = 0.0001,
) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $expected +/- $absoluteTolerance but was $actual",
    )
}
