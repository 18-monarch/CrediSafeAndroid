package com.credisafe.mobile

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
    fun goldenVector1_score50_20mins_confidence1_0() {
        val result = XpEngine.calculate(
            safetyScore = 50,
            distanceM = 5000.0,
            durationMs = 1200_000L,
            gpsQuality = 1.0,
            telemetryQuality = 1.0,
        )
        assertTrue(result.eligible)
        assertEquals(8, result.total)
        assertEquals(4, result.rewardPoints)
    }

    @Test
    fun goldenVector2_score92_20mins_confidence0_95() {
        val result = XpEngine.calculate(
            safetyScore = 92,
            distanceM = 5000.0,
            durationMs = 1200_000L,
            gpsQuality = 0.95,
            telemetryQuality = 0.95,
        )
        assertTrue(result.eligible)
        assertEquals(27, result.total)
        assertEquals(13, result.rewardPoints)
    }

    @Test
    fun goldenVector3_score95_20mins_confidence1_0() {
        val result = XpEngine.calculate(
            safetyScore = 95,
            distanceM = 5000.0,
            durationMs = 1200_000L,
            gpsQuality = 1.0,
            telemetryQuality = 1.0,
        )
        assertTrue(result.eligible)
        assertEquals(30, result.total)
        assertEquals(15, result.rewardPoints)
    }

    @Test
    fun goldenVector4_minimumExposure_2mins() {
        val result = XpEngine.calculate(
            safetyScore = 100,
            distanceM = 1000.0,
            durationMs = 120_000L,
            gpsQuality = 1.0,
            telemetryQuality = 1.0,
        )
        assertTrue(result.eligible)
        assertEquals(23, result.total)
        assertEquals(11, result.rewardPoints)
    }

    @Test
    fun goldenVector5_maximumExposure_60mins() {
        val result = XpEngine.calculate(
            safetyScore = 100,
            distanceM = 20000.0,
            durationMs = 3600_000L,
            gpsQuality = 1.0,
            telemetryQuality = 1.0,
        )
        assertTrue(result.eligible)
        assertEquals(38, result.total)
        assertEquals(19, result.rewardPoints)
    }

    @Test
    fun goldenVector6_minTelemetryConfidence_0_35() {
        // completionBase + safetyBonus = 8 + 22 = 30
        // qualityFactor = clamp(0.35, 0.70, 1.00) = 0.70
        // round(30 * 1.00 * 0.70) = 21 XP
        val result = XpEngine.calculate(
            safetyScore = 100,
            distanceM = 5000.0,
            durationMs = 1200_000L,
            gpsQuality = 1.0,
            telemetryQuality = 0.35,
        )
        assertTrue(result.eligible)
        assertEquals(21, result.total)
    }

    @Test
    fun goldenVector7_xpLowerClampEnforced() {
        val result = XpEngine.calculate(
            safetyScore = 50,
            distanceM = 600.0,
            durationMs = 120_000L,
            gpsQuality = 0.35,
            telemetryQuality = 0.35,
        )
        assertTrue(result.eligible)
        assertEquals(5, result.total)
    }

    @Test
    fun goldenVector8_xpUpperClampEnforced() {
        val result = XpEngine.calculate(
            safetyScore = 100,
            distanceM = 25000.0,
            durationMs = 3600_000L,
            gpsQuality = 1.0,
            telemetryQuality = 1.0,
        )
        assertTrue(result.eligible)
        assertEquals(38, result.total)
    }

    @Test
    fun goldenVector9_ineligibleTripReturnsZeroXp() {
        val result = XpEngine.calculate(
            safetyScore = 100,
            distanceM = 100.0,
            durationMs = 180_000L,
            gpsQuality = 0.95,
        )
        assertEquals(0, result.total)
        assertEquals(0, result.rewardPoints)
        assertTrue(!result.eligible)
    }

    @Test
    fun levelProgressionThresholdBoundariesMatchBackend() {
        assertEquals(0, XpEngine.totalXpRequiredForLevel(1))
        assertEquals(100, XpEngine.totalXpRequiredForLevel(2))
        assertEquals(283, XpEngine.totalXpRequiredForLevel(3))
        assertEquals(520, XpEngine.totalXpRequiredForLevel(4))
        assertEquals(800, XpEngine.totalXpRequiredForLevel(5))
        assertEquals(1118, XpEngine.totalXpRequiredForLevel(6))
        assertEquals(1470, XpEngine.totalXpRequiredForLevel(7))
        assertEquals(1852, XpEngine.totalXpRequiredForLevel(8))
        assertEquals(2263, XpEngine.totalXpRequiredForLevel(9))
        assertEquals(2700, XpEngine.totalXpRequiredForLevel(10))

        assertEquals(1, XpEngine.calculateLevelInfo(0).currentLevel)
        assertEquals(1, XpEngine.calculateLevelInfo(99).currentLevel)
        assertEquals(2, XpEngine.calculateLevelInfo(100).currentLevel)
        assertEquals(2, XpEngine.calculateLevelInfo(282).currentLevel)
        assertEquals(3, XpEngine.calculateLevelInfo(283).currentLevel)
        assertEquals(3, XpEngine.calculateLevelInfo(519).currentLevel)
        assertEquals(4, XpEngine.calculateLevelInfo(520).currentLevel)
        assertEquals(4, XpEngine.calculateLevelInfo(799).currentLevel)
        assertEquals(5, XpEngine.calculateLevelInfo(800).currentLevel)
        assertEquals(5, XpEngine.calculateLevelInfo(1117).currentLevel)
        assertEquals(6, XpEngine.calculateLevelInfo(1118).currentLevel)
        assertEquals(6, XpEngine.calculateLevelInfo(1469).currentLevel)
        assertEquals(7, XpEngine.calculateLevelInfo(1470).currentLevel)
        assertEquals(7, XpEngine.calculateLevelInfo(1851).currentLevel)
        assertEquals(8, XpEngine.calculateLevelInfo(1852).currentLevel)
        assertEquals(8, XpEngine.calculateLevelInfo(2262).currentLevel)
        assertEquals(9, XpEngine.calculateLevelInfo(2263).currentLevel)
        assertEquals(9, XpEngine.calculateLevelInfo(2699).currentLevel)
        assertEquals(10, XpEngine.calculateLevelInfo(2700).currentLevel)
    }
}
