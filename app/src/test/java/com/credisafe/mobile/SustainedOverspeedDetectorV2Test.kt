package com.credisafe.mobile

import com.credisafe.mobile.domain.OverspeedLevel
import com.credisafe.mobile.domain.RoadContext
import com.credisafe.mobile.domain.SustainedOverspeedDetectorV2
import org.junit.Assert.assertEquals
import org.junit.Test

class SustainedOverspeedDetectorV2Test {
    private fun trusted(now: Long) = RoadContext(
        speedLimitKmh = 50.0,
        speedLimitTrusted = true,
        confidence = 0.95,
        updatedAtMs = now,
    )

    @Test
    fun singleSpeedSpikeIsNotPenalized() {
        val detector = SustainedOverspeedDetectorV2()
        assertEquals(OverspeedLevel.NONE, detector.observe(60.0, 10000, trusted(10000)).level)
        assertEquals(OverspeedLevel.NONE, detector.observe(48.0, 11000, trusted(11000)).level)
    }

    @Test
    fun sustainedTrustedOverspeedConfirmsAfterThreeSeconds() {
        val detector = SustainedOverspeedDetectorV2()
        val base = 10000L
        assertEquals(OverspeedLevel.NONE, detector.observe(77.0, base, trusted(base)).level)
        assertEquals(OverspeedLevel.NONE, detector.observe(77.0, base + 1000, trusted(base + 1000)).level)
        assertEquals(OverspeedLevel.NONE, detector.observe(77.0, base + 2000, trusted(base + 2000)).level)
        assertEquals(OverspeedLevel.MAJOR, detector.observe(77.0, base + 3000, trusted(base + 3000)).level)
        assertEquals(OverspeedLevel.NONE, detector.observe(77.0, base + 4000, trusted(base + 4000)).level)
    }

    @Test
    fun unknownLimitCannotGeneratePenalty() {
        val detector = SustainedOverspeedDetectorV2()
        for (i in 0..10) {
            val now = 10000L + i * 1000L
            assertEquals(OverspeedLevel.NONE, detector.observe(120.0, now, RoadContext(updatedAtMs = now)).level)
        }
    }

    @Test
    fun gpsGapResetsSustainedEvidence() {
        val detector = SustainedOverspeedDetectorV2()
        detector.observe(63.0, 10000, trusted(10000))
        detector.observe(63.0, 11000, trusted(11000))
        assertEquals(OverspeedLevel.NONE, detector.observe(63.0, 16000, trusted(16000)).level)
        assertEquals(OverspeedLevel.NONE, detector.observe(63.0, 17000, trusted(17000)).level)
    }
}
