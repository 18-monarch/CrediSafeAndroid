package com.credisafe.mobile

import com.credisafe.mobile.domain.MotionEventDetectorV2
import com.credisafe.mobile.domain.MotionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEventDetectorV2Test {
    @Test
    fun singleSensorSpikeNeverTriggers() {
        val detector = MotionEventDetectorV2()
        val result = mutableListOf<MotionKind>()
        for (i in 0..50) {
            val a = if (i == 15) -8.0 else 0.0
            result += detector.observe(i * 20L, a, 0.0, 42.0, true).map { it.kind }
        }
        assertTrue(result.isEmpty())
    }

    @Test
    fun sustainedRealBrakingTriggersOnce() {
        val detector = MotionEventDetectorV2()
        val result = mutableListOf<MotionKind>()
        for (i in 0..100) {
            result += detector.observe(i * 20L, -4.5, 0.0, 45.0, true).map { it.kind }
        }
        assertEquals(listOf(MotionKind.HARSH_BRAKING), result)
    }

    @Test
    fun unreliableOrientationSuppressesAndClearsCandidate() {
        val detector = MotionEventDetectorV2()
        for (i in 0..16) detector.observe(i * 20L, -5.0, 0.0, 50.0, true)
        val suppressed = detector.observe(17 * 20L, -5.0, 0.0, 50.0, false)
        assertTrue(suppressed.isEmpty())
        // After GPS loss the detector must require a new sustained window.
        assertTrue(detector.observe(18 * 20L, -5.0, 0.0, 50.0, true).isEmpty())
    }

    @Test
    fun corneringRespectsMinimumSpeedAndDwell() {
        val detector = MotionEventDetectorV2()
        for (i in 0..60) {
            assertTrue(detector.observe(i * 20L, 0.0, 5.0, 15.0, true).isEmpty())
        }
        val results = (61..140).flatMap {
            detector.observe(it * 20L, 0.0, 5.0, 40.0, true)
        }
        assertEquals(1, results.count { it.kind == MotionKind.AGGRESSIVE_CORNERING })
    }

    @Test
    fun nonMonotonicSamplesDoNotCreateEvents() {
        val detector = MotionEventDetectorV2()
        detector.observe(100L, 0.0, 0.0, 50.0, true)
        assertTrue(detector.observe(100L, -12.0, 0.0, 50.0, true).isEmpty())
        assertTrue(detector.observe(90L, -12.0, 0.0, 50.0, true).isEmpty())
    }
}
