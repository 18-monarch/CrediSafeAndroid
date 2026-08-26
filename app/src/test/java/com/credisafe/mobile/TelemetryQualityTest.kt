package com.credisafe.mobile

import com.credisafe.mobile.domain.TelematicsQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryQualityTest {
    @Test
    fun highQualitySensorsAndGpsProducePerfectScore() {
        val result = TelematicsQuality.calculate(
            gpsQuality = 1.0,
            sensorSamples = 5000,
            elapsedMs = 100_000, // 50 Hz
            suspiciousJumps = 0,
            mockLocations = 0,
            maxSpeedKmh = 100.0
        )
        assertEquals(1.0, result.overall, 0.01)
    }

    @Test
    fun mockLocationSeverelyReducesQuality() {
        val result = TelematicsQuality.calculate(
            gpsQuality = 1.0,
            sensorSamples = 5000,
            elapsedMs = 100_000,
            suspiciousJumps = 0,
            mockLocations = 1,
            maxSpeedKmh = 100.0
        )
        assertTrue(result.overall < 0.7)
    }

    @Test
    fun lowSampleRateReducesQuality() {
        val result = TelematicsQuality.calculate(
            gpsQuality = 1.0,
            sensorSamples = 500, // 5 Hz instead of 50 Hz
            elapsedMs = 100_000,
            suspiciousJumps = 0,
            mockLocations = 0,
            maxSpeedKmh = 100.0
        )
        assertTrue(result.overall < 0.9)
    }
}
