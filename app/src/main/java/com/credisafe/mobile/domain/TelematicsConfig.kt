package com.credisafe.mobile.domain

/**
 * Pilot telemetry configuration.
 *
 * Keep these values centralized so the faculty pilot can be calibrated from
 * measured data without rewriting the telemetry service.
 */
object TelematicsConfig {
    const val LOCATION_INTERVAL_MS = 1000L
    const val MIN_LOCATION_INTERVAL_MS = 500L
    const val MIN_LOCATION_DISTANCE_M = 2f
    const val MAX_LOCATION_DELAY_MS = 2000L

    const val SENSOR_PERIOD_US = 20_000 // ~50 Hz input where available.
    const val SENSOR_MAX_LATENCY_US = 100_000
    const val STORAGE_INTERVAL_MS = 100L // ~10 Hz persisted telemetry.

    const val SPEED_LIMIT_KMH = 60.0
    const val MINOR_OVERSPEED_KMH = SPEED_LIMIT_KMH + 5.0
    const val MAJOR_OVERSPEED_KMH = SPEED_LIMIT_KMH + 20.0

    const val HARSH_BRAKING_MPS2 = -2.5
    const val HARSH_ACCELERATION_MPS2 = 2.7
    const val AGGRESSIVE_LATERAL_MPS2 = 3.5

    const val EVENT_COOLDOWN_MS = 8_000L
    const val MIN_MOVING_SPEED_KMH = 5.0
    const val MIN_CORNER_SPEED_KMH = 25.0

    const val MAX_REASONABLE_SPEED_KMH = 220.0
    const val MAX_REASONABLE_SEGMENT_M = 1_500.0
}
