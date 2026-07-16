package com.phonetts.core.audio

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 9.5 — proves the sample rate flowing into both consumers is the value the caller passed in
 * (i.e. read from [com.phonetts.core.model.ModelDescriptor.sampleRate]), never a hardcoded
 * constant. Parametrized over two different rates so a hardcoded value cannot pass both.
 */
class SampleRateRoutingTest {
    @Test
    fun wavHeaderAndStreamingSinkBothCarryTheDescriptorSampleRateAt22050() =
        runTest {
            assertBothConsumersRouteSampleRate(22_050)
        }

    @Test
    fun wavHeaderAndStreamingSinkBothCarryTheDescriptorSampleRateAt24000() =
        runTest {
            assertBothConsumersRouteSampleRate(24_000)
        }

    private suspend fun assertBothConsumersRouteSampleRate(sampleRate: Int) {
        val chunks = listOf(floatArrayOf(0.1f, -0.1f), floatArrayOf(0.2f, -0.2f))

        val out = ByteArrayOutputStream()
        WavWriter().write(flowOf(*chunks.toTypedArray()), sampleRate, out)
        val wavSampleRate = parseWav(out.toByteArray()).sampleRate

        val sink = RecordingSink()
        StreamingConsumer().play(flowOf(*chunks.toTypedArray()), sampleRate, sink)

        assertEquals(sampleRate, wavSampleRate)
        assertEquals(sampleRate, sink.sampleRate)
    }
}
