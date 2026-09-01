package com.credisafe.mobile

import com.credisafe.mobile.domain.MobilityDecision
import com.credisafe.mobile.domain.TransportMode
import com.credisafe.mobile.domain.TripClassification
import com.credisafe.mobile.domain.TripValidityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripValidityEngineTest {
    @Test
    fun tinyAccidentalTripIsNoise() {
        val result = TripValidityEngine.assess(
            distanceM = 80.0,
            durationMs = 35_000,
            gpsQuality = 0.9,
            telemetryQuality = 0.9,
            movingLocationRatio = 0.05,
            locationSamples = 20,
            antiGamingFlags = emptyList(),
            nowMs = 1000L,
        )
        assertEquals(TripClassification.NOISE, result.classification)
        assertFalse(result.eligible)
        assertFalse(result.shouldSync)
        assertTrue(result.discardAfterMs != null)
    }

    @Test
    fun walkingTripGetsNoDrivingEligibility() {
        val result = TripValidityEngine.assess(
            distanceM = 1_200.0,
            durationMs = 900_000,
            gpsQuality = 0.95,
            telemetryQuality = 0.9,
            movingLocationRatio = 0.8,
            locationSamples = 600,
            antiGamingFlags = emptyList(),
            mobility = MobilityDecision(
                mode = TransportMode.WALKING,
                confidence = 95,
                drivingEligible = false,
                reason = "walking",
            ),
        )
        assertEquals(TripClassification.INVALID, result.classification)
        assertFalse(result.eligible)
    }
}
