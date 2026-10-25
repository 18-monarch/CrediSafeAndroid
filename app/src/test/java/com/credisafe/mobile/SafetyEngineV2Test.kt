package com.credisafe.mobile

import com.credisafe.mobile.data.EventType
import com.credisafe.mobile.domain.SafetyEngine
import com.credisafe.mobile.domain.TelematicsQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEngineV2Test {
    @Test
    fun behaviourScoreCountsOneBrakeOnce() {
        assertEquals(93, SafetyEngine.scoreV2(mapOf(EventType.HARSH_BRAKING to 1)))
    }

    @Test
    fun behaviourScoreDoesNotTreatPoorGpsAsUnsafeDriving() {
        assertEquals(100, SafetyEngine.scoreV2(emptyMap()))
        // Quality remains a separate measurement rather than a driving penalty.
        val low = TelematicsQuality.calculate(0.25, 2000, 60000, 0, 0, 50.0)
        val high = TelematicsQuality.calculate(0.95, 3000, 60000, 0, 0, 50.0)
        assertTrue(low.overall < high.overall)
    }

    @Test
    fun trueSensorInputRateCanEarnHighestSamplingGrade() {
        // 50 Hz sensor events for a 60-second trip, even though we store
        // only about 10 sensor snapshots per second.
        val output = TelematicsQuality.calculate(0.95, 3000, 60000, 0, 0, 50.0)
        assertEquals(1.0, output.sensorScore, 0.0001)
    }
}
