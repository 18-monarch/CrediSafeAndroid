package com.credisafe.mobile

import com.credisafe.mobile.data.DrivingEvent
import com.credisafe.mobile.data.EventSeverity
import com.credisafe.mobile.data.EventType
import com.credisafe.mobile.domain.SafetyEngine
import com.credisafe.mobile.domain.XpEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEngineTest {
    @Test
    fun cleanTripScoresHigh() {
        assertEquals(100, SafetyEngine.score(emptyMap(), 0.95, 5000.0, 0.95))
    }

    @Test
    fun brakingReducesScore() {
        val score = SafetyEngine.score(
            mapOf(EventType.HARSH_BRAKING to 1),
            0.95,
            5000.0,
            0.95,
        )
        assertTrue(score < 100)
    }

    @Test
    fun ineligibleTripGetsZeroXp() {
        val result = XpEngine.calculate(
            mode = com.credisafe.mobile.domain.TripMode.REAL_GPS,
            safetyScore = 100,
            distanceM = 100.0,
            durationMs = 180_000,
            gpsQuality = 0.95,
            events = emptyList(),
            streakDays = 1,
            firstTripOfDay = true,
            antiGamingFlags = emptyList(),
        )
        assertEquals(0, result.total)
        assertEquals(0, result.rewardPoints)
        assertTrue(!result.eligible)
    }

    @Test
    fun cleanGpsTripGetsTransparentBreakdownAndRewardConversion() {
        val result = XpEngine.calculate(
            mode = com.credisafe.mobile.domain.TripMode.REAL_GPS,
            safetyScore = 97,
            distanceM = 8400.0,
            durationMs = 14 * 60_000L,
            gpsQuality = 0.98,
            events = emptyList(),
            streakDays = 1,
            firstTripOfDay = true,
            antiGamingFlags = emptyList(),
        )
        assertTrue(result.eligible)
        assertEquals(191, result.total)
        assertEquals(95, result.rewardPoints)
        assertEquals(5, result.items.size)
        assertTrue(result.items.any { it.code == "clean_trip" })
    }

    @Test
    fun xpCapIsApplied() {
        val events = listOf(
            DrivingEvent("t", 1, EventType.HARSH_ACCELERATION, EventSeverity.LOW, 0.9, 40.0, 3.0, 0.0),
        )
        val result = XpEngine.calculate(
            mode = com.credisafe.mobile.domain.TripMode.REAL_GPS,
            safetyScore = 97,
            distanceM = 50_000.0,
            durationMs = 20 * 60_000L,
            gpsQuality = 0.98,
            events = events,
            streakDays = 7,
            firstTripOfDay = true,
            antiGamingFlags = emptyList(),
        )
        assertEquals(XpEngine.TRIP_CAP, result.total)
        assertEquals(XpEngine.TRIP_CAP / 2, result.rewardPoints)
    }
}
