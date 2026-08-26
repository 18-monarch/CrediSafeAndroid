package com.credisafe.mobile

import com.credisafe.mobile.domain.TelemetryMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryMathTest {
    @Test
    fun fallbackKeepsDeviceFrameWhenRotationIsUnavailable() {
        val world = TelemetryMath.worldAcceleration(
            rotationVector = null,
            linearAcceleration = doubleArrayOf(1.0, -2.0, 0.5),
        )
        assertEquals(1.0, world.north, 0.0001)
        assertEquals(-2.0, world.east, 0.0001)
        assertEquals(0.5, world.up, 0.0001)
    }

    @Test
    fun zeroBearingMapsNorthAccelerationToLongitudinal() {
        val world = com.credisafe.mobile.domain.WorldAcceleration(2.0, 0.0, 0.0)
        val vehicle = TelemetryMath.vehicleAcceleration(world, 0.0)
        assertEquals(2.0, vehicle.longitudinal, 0.0001)
        assertEquals(0.0, vehicle.lateral, 0.0001)
    }

    @Test
    fun eastAccelerationAtEastHeadingBecomesLongitudinal() {
        val world = com.credisafe.mobile.domain.WorldAcceleration(0.0, 3.0, 0.0)
        val vehicle = TelemetryMath.vehicleAcceleration(world, 90.0)
        assertEquals(3.0, vehicle.longitudinal, 0.0001)
        assertTrue(kotlin.math.abs(vehicle.lateral) < 0.0001)
    }
}
