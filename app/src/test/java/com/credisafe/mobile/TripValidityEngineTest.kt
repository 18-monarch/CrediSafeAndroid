package com.credisafe.mobile

import com.credisafe.mobile.domain.TripClassification
import com.credisafe.mobile.domain.TripValidityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripValidityEngineTest {
    @Test
    fun accidentalTinyTripIsNoiseAndAutoDiscarded() {
        val result = TripValidityEngine.assess(
            distanceM = 80.0,
            durationMs = 35_000,
            gpsQuality = 0.9,
            telemetryQuality = 0.9,
            movingLocationRatio = 0.05,
            locationSamples = 20,
            antiGamingFlags = emptyList(),
            nowMs = 1_000L,
        )

        assertEquals(TripClassification.NOISE, result.classification)
        assertFalse(result.eligible)
        assertFalse(result.shouldSync)
        assertFalse(result.shouldShowInHistory)
        assertTrue(result.discardAfterMs != null && result.discardAfterMs!! > 1_000L)
    }

    @Test
    fun shortButMeaningfulTripIsInvalidNotNoise() {
        val result = TripValidityEngine.assess(
            distanceM = 420.0,
            durationMs = 180_000,
            gpsQuality = 0.95,
            telemetryQuality = 0.95,
            movingLocationRatio = 0.8,
            locationSamples = 150,
            antiGamingFlags = emptyList(),
        )

        assertEquals(TripClassification.INVALID, result.classification)
        assertFalse(result.eligible)
        assertTrue(result.shouldShowInHistory)
    }

    @Test
    fun realHealthyTripIsEligible() {
        val result = TripValidityEngine.assess(
            distanceM = 8_500.0,
            durationMs = 900_000,
            gpsQuality = 0.93,
            telemetryQuality = 0.91,
            movingLocationRatio = 0.75,
            locationSamples = 700,
            antiGamingFlags = emptyList(),
        )

        assertEquals(TripClassification.ELIGIBLE, result.classification)
        assertTrue(result.eligible)
        assertTrue(result.shouldSync)
    }

    @Test
    fun mockLocationTripRequiresReview() {
        val result = TripValidityEngine.assess(
            distanceM = 7_000.0,
            durationMs = 800_000,
            gpsQuality = 0.95,
            telemetryQuality = 0.9,
            movingLocationRatio = 0.7,
            locationSamples = 500,
            antiGamingFlags = listOf("mock_location:1"),
        )

        assertEquals(TripClassification.SUSPICIOUS, result.classification)
        assertFalse(result.eligible)
        assertFalse(result.shouldSync)
    }
}
