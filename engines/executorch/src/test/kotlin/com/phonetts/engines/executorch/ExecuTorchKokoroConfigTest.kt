package com.phonetts.engines.executorch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecuTorchKokoroConfigTest {
    @Test
    fun parsesTheRealRepoShapeWhichHasOnlyAModelNameField() {
        // VERIFIED: the real HF react-native-executorch-kokoro config.json is exactly this.
        val parsed = ExecuTorchKokoroConfig.parse("""{"modelName": "kokoro"}""")

        assertEquals("kokoro", parsed.family)
        assertNull(parsed.sampleRate)
        assertNull(parsed.speedMin)
    }

    @Test
    fun parsesACuratedBundlesFullOverrideSet() {
        val text =
            """{"family": "executorch-kokoro", "sample_rate": 24000, "speed_min": 0.5, """ +
                """"speed_max": 2.0, "default_voice": "af_heart", "default_speed": 1.2}"""

        val parsed = ExecuTorchKokoroConfig.parse(text)

        assertEquals("executorch-kokoro", parsed.family)
        assertEquals(24_000, parsed.sampleRate)
        assertEquals(0.5f, parsed.speedMin)
        assertEquals(2.0f, parsed.speedMax)
        assertEquals("af_heart", parsed.defaultVoiceId)
        assertEquals(1.2f, parsed.defaultSpeed)
    }

    @Test
    fun malformedTextYieldsAllNullsRatherThanThrowing() {
        val parsed = ExecuTorchKokoroConfig.parse("not json")

        assertNull(parsed.family)
        assertNull(parsed.sampleRate)
    }
}
