package com.credisafe.mobile.domain

import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin

data class WorldAcceleration(
    val north: Double,
    val east: Double,
    val up: Double,
)

data class VehicleAcceleration(
    val longitudinal: Double,
    val lateral: Double,
    val vertical: Double,
)

object TelemetryMath {
    fun worldAcceleration(
        rotationVector: FloatArray?,
        linearAcceleration: DoubleArray,
    ): WorldAcceleration {
        val x = linearAcceleration.getOrElse(0) { 0.0 }
        val y = linearAcceleration.getOrElse(1) { 0.0 }
        val z = linearAcceleration.getOrElse(2) { 0.0 }

        if (rotationVector == null || rotationVector.size < 3 || rotationVector.any { !it.isFinite() }) {
            return WorldAcceleration(x, y, z)
        }

        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, rotationVector)

        val north = matrix[0] * x + matrix[1] * y + matrix[2] * z
        val east = matrix[3] * x + matrix[4] * y + matrix[5] * z
        val up = matrix[6] * x + matrix[7] * y + matrix[8] * z
        return WorldAcceleration(north.toDouble(), east.toDouble(), up.toDouble())
    }

    fun vehicleAcceleration(
        world: WorldAcceleration,
        bearingDeg: Double?,
    ): VehicleAcceleration {
        if (bearingDeg == null || !bearingDeg.isFinite()) {
            return VehicleAcceleration(world.north, world.east, world.up)
        }

        val radians = Math.toRadians(bearingDeg)
        val longitudinal = world.north * cos(radians) + world.east * sin(radians)
        val lateral = -world.north * sin(radians) + world.east * cos(radians)
        return VehicleAcceleration(longitudinal, lateral, world.up)
    }
}
