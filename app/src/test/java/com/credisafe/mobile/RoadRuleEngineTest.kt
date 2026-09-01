package com.credisafe.mobile

import com.credisafe.mobile.domain.OverspeedLevel
import com.credisafe.mobile.domain.RoadContext
import com.credisafe.mobile.domain.RoadContextSource
import com.credisafe.mobile.domain.RoadRuleEngine
import com.credisafe.mobile.domain.RoadZoneType
import org.junit.Assert.assertEquals
import org.junit.Test

class RoadRuleEngineTest {
    @Test
    fun unknownLimitNeverCreatesFalseOverspeed() {
        val decision = RoadRuleEngine.overspeed(
            speedKmh = 110.0,
            context = RoadContext(
                zoneType = RoadZoneType.HIGHWAY,
                speedLimitKmh = null,
                speedLimitTrusted = false,
                confidence = 0.9,
                source = RoadContextSource.VALHALLA_OSM,
                updatedAtMs = 1000L,
            ),
            nowMs = 2000L,
        )
        assertEquals(OverspeedLevel.NONE, decision.level)
    }

    @Test
    fun sameSpeedUsesActualTrustedRoadLimit() {
        val now = 10_000L
        val highLimit = RoadContext(
            zoneType = RoadZoneType.HIGHWAY,
            speedLimitKmh = 100.0,
            speedLimitTrusted = true,
            confidence = 0.95,
            updatedAtMs = now,
        )
        val lowLimit = RoadContext(
            zoneType = RoadZoneType.URBAN,
            speedLimitKmh = 50.0,
            speedLimitTrusted = true,
            confidence = 0.95,
            updatedAtMs = now,
        )
        assertEquals(OverspeedLevel.NONE, RoadRuleEngine.overspeed(82.0, highLimit, now).level)
        assertEquals(OverspeedLevel.MAJOR, RoadRuleEngine.overspeed(82.0, lowLimit, now).level)
    }
}
