package com.credisafe.mobile.data

import kotlinx.serialization.Serializable

enum class EventType {
    OVERSPEED_MINOR,
    OVERSPEED_MAJOR,
    HARSH_BRAKING,
    HARSH_ACCELERATION,
    AGGRESSIVE_CORNERING,
    GPS_ANOMALY,
    TELEMETRY_ANOMALY
}

enum class EventSeverity {
    LOW,
    MEDIUM,
    HIGH
}

data class DrivingEvent(
    val tripId: String,
    val timestampMs: Long,
    val type: EventType,
    val severity: EventSeverity,
    val confidence: Double,
    val speedKmh: Double,
    val longitudinalAccel: Double,
    val lateralAccel: Double,
    val detail: String? = null,
)

@Serializable
data class SensorSample(
    val tripId: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Double?,
    val speedKmh: Double?,
    val bearingDeg: Double?,
    val ax: Double?,
    val ay: Double?,
    val az: Double?,
    val gx: Double?,
    val gy: Double?,
    val gz: Double?,
    val lax: Double?,
    val lay: Double?,
    val laz: Double?,
    val rx: Double?,
    val ry: Double?,
    val rz: Double?,
    val rw: Double?,
    val longAcc: Double?,
    val latAcc: Double?,
    val verticalAcc: Double? = null,
    val jerkLong: Double? = null,
    val jerkLat: Double? = null,
)

data class TripSummary(
    val endedAt: Long,
    val distanceM: Double,
    val durationMs: Long,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val gpsQuality: Double,
    val sensorQuality: Double,
    val safetyScore: Int,
    val xp: Int,
    val rewardPoints: Int,
    val xpBreakdownJson: String = "",
    val eligibilityReason: String = "",
    val telemetryQuality: Double = 0.0,
    val antiGamingFlagsJson: String = "[]",
    val engineVersion: String = "",
    val tripClassification: String = "ELIGIBLE",
    val discardAfterMs: Long? = null,
    val roadZoneType: String = "UNKNOWN",
    val roadName: String? = null,
    val roadPlaceId: String? = null,
    val roadSpeedLimitKmh: Double? = null,
    val roadContextConfidence: Double = 0.0,
    val roadContextSource: String = "NONE",
)

@Serializable
data class TripRecord(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceM: Double,
    val durationMs: Long,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val gpsQuality: Double,
    val sensorQuality: Double,
    val safetyScore: Int?,
    val xp: Int?,
    val rewardPoints: Int?,
    val status: String,
    val xpBreakdownJson: String? = null,
    val eligibilityReason: String? = null,
    val telemetryQuality: Double? = null,
    val antiGamingFlagsJson: String? = null,
    val engineVersion: String? = null,
    val tripClassification: String = "ELIGIBLE",
    val discardAfterMs: Long? = null,
    val roadZoneType: String = "UNKNOWN",
    val roadName: String? = null,
    val roadPlaceId: String? = null,
    val roadSpeedLimitKmh: Double? = null,
    val roadContextConfidence: Double = 0.0,
    val roadContextSource: String = "NONE",
    val syncStatus: String = "PENDING",
) {
    val isAuthoritative: Boolean get() = syncStatus == "SYNCED"
}
