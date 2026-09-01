package com.credisafe.mobile

import com.credisafe.mobile.domain.MobilityAccumulator
import com.credisafe.mobile.domain.MobilityEngine
import com.credisafe.mobile.domain.MobilitySnapshot
import com.credisafe.mobile.domain.RecognizedActivity
import com.credisafe.mobile.domain.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilityEngineTest {
    @Test
    fun walkingBlocksDrivingXpWhenConfidenceIsHigh() {
        val decision = MobilityEngine.decide(
            dominantActivity = RecognizedActivity.WALKING,
            activityConfidence = 92,
            roadProviderSamples = 0,
            roadMatchedSamples = 0,
            avgSpeedKmh = 4.5,
            maxSpeedKmh = 7.0,
        )
        assertEquals(TransportMode.WALKING, decision.mode)
        assertFalse(decision.drivingEligible)
    }

    @Test
    fun inVehiclePlusRoadMatchBecomesDriving() {
        val decision = MobilityEngine.decide(
            dominantActivity = RecognizedActivity.IN_VEHICLE,
            activityConfidence = 88,
            roadProviderSamples = 10,
            roadMatchedSamples = 9,
            avgSpeedKmh = 42.0,
            maxSpeedKmh = 76.0,
        )
        assertEquals(TransportMode.DRIVING, decision.mode)
        assertTrue(decision.drivingEligible)
    }

    @Test
    fun possibleRailRequiresRepeatedProviderEvidence() {
        val decision = MobilityEngine.decide(
            dominantActivity = RecognizedActivity.IN_VEHICLE,
            activityConfidence = 90,
            roadProviderSamples = 10,
            roadMatchedSamples = 0,
            avgSpeedKmh = 55.0,
            maxSpeedKmh = 90.0,
        )
        assertEquals(TransportMode.POSSIBLE_RAIL_TRANSIT, decision.mode)
        assertFalse(decision.drivingEligible)
    }


    @Test
    fun staleActivitySamplesAreIgnored() {
        val accumulator = MobilityAccumulator()
        accumulator.add(
            MobilitySnapshot(
                activity = RecognizedActivity.WALKING,
                confidence = 99,
                updatedAtMs = 1_000L,
            ),
            nowMs = 200_000L,
        )
        accumulator.add(
            MobilitySnapshot(
                activity = RecognizedActivity.IN_VEHICLE,
                confidence = 88,
                updatedAtMs = 199_000L,
            ),
            nowMs = 200_000L,
        )

        val (activity, confidence) = accumulator.dominant()
        assertEquals(RecognizedActivity.IN_VEHICLE, activity)
        assertEquals(88, confidence)
    }

    @Test
    fun noRoadProviderDoesNotPretendTrain() {
        val decision = MobilityEngine.decide(
            dominantActivity = RecognizedActivity.IN_VEHICLE,
            activityConfidence = 85,
            roadProviderSamples = 0,
            roadMatchedSamples = 0,
            avgSpeedKmh = 60.0,
            maxSpeedKmh = 100.0,
        )
        assertEquals(TransportMode.ROAD_VEHICLE, decision.mode)
        assertTrue(decision.drivingEligible)
    }
}
