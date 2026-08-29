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
                source = RoadContextSource.GOOGLE_ROADS,
                updatedAtMs = 1_000L,
            ),
            nowMs = 2_000L,
        )

        assertEquals(OverspeedLevel.NONE, decision.level)
    }

    @Test
    fun sameSpeedCanBeSafeOnHighLimitAndOverspeedOnLowLimit() {
        val now = 10_000L
        val highway = RoadContext(
            zoneType = RoadZoneType.HIGHWAY,
            speedLimitKmh = 100.0,
            speedLimitTrusted = true,
            confidence = 0.95,
            source = RoadContextSource.GOOGLE_ROADS_SPEED_LIMIT,
            updatedAtMs = now,
        )
        val urban = RoadContext(
            zoneType = RoadZoneType.URBAN,
            speedLimitKmh = 50.0,
            speedLimitTrusted = true,
            confidence = 0.95,
            source = RoadContextSource.GOOGLE_ROADS_SPEED_LIMIT,
            updatedAtMs = now,
        )

        assertEquals(OverspeedLevel.NONE, RoadRuleEngine.overspeed(82.0, highway, now).level)
        assertEquals(OverspeedLevel.MAJOR, RoadRuleEngine.overspeed(82.0, urban, now).level)
    }

    @Test
    fun staleRoadContextDoesNotPenalizeDriver() {
        val context = RoadContext(
            zoneType = RoadZoneType.URBAN,
            speedLimitKmh = 50.0,
            speedLimitTrusted = true,
            confidence = 0.95,
            source = RoadContextSource.GOOGLE_ROADS_SPEED_LIMIT,
            updatedAtMs = 1_000L,
        )

        assertEquals(
            OverspeedLevel.NONE,
            RoadRuleEngine.overspeed(90.0, context, nowMs = 100_000L).level,
        )
    }
}
