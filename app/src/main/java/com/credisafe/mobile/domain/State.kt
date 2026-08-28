package com.credisafe.mobile.domain

import com.credisafe.mobile.data.DrivingEvent
import com.credisafe.mobile.data.EventType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class StreamStatus { DISCONNECTED, CONNECTING, LIVE, RECONNECTING, ERROR }

data class LatLngPoint(val lat: Double, val lng: Double)

data class LiveTelemetry(
    val active: Boolean = false,
    val tripId: String? = null,
    val streamStatus: StreamStatus = StreamStatus.DISCONNECTED,
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val gpsAccuracyM: Double? = null,
    val gpsQuality: Double = 0.0,
    val longAcc: Double = 0.0,
    val latAcc: Double = 0.0,
    val verticalAcc: Double = 0.0,
    val sensorCount: Long = 0,
    val locationCount: Long = 0,
    val eventCount: Int = 0,
    val safetyScore: Int = 100,
    val telemetryQuality: Double = 0.0,
    val latestEvent: DrivingEvent? = null,
    val lastError: String? = null,
    // Diagnostics
    val rawAx: Double = 0.0,
    val rawAy: Double = 0.0,
    val rawAz: Double = 0.0,
    val rawGx: Double = 0.0,
    val rawGy: Double = 0.0,
    val rawGz: Double = 0.0,
    val worldNorth: Double = 0.0,
    val worldEast: Double = 0.0,
    val worldUp: Double = 0.0,
    val jerkLong: Double = 0.0,
    val jerkLat: Double = 0.0,
    val sensorHz: Double = 0.0,
    val sensorJitterMs: Double = 0.0,
    val processLatencyMs: Double = 0.0,
    val route: List<LatLngPoint> = emptyList(),
)

data class CompatibilityState(
    val sensorsReady: Boolean = false,
    val locationReady: Boolean = false,
    val batteryOptimized: Boolean = false,
    val issues: List<CompatibilityIssue> = emptyList()
)

data class CompatibilityIssue(
    val level: IssueLevel,
    val label: String,
    val suggestion: String,
    val actionIntent: String? = null
)

enum class IssueLevel { CRITICAL, WARNING, INFO }

object SafetyEngine {
    fun score(
        counts: Map<EventType, Int>,
        gpsQuality: Double,
        distanceM: Double,
        sensorQuality: Double = 1.0,
        anomalyPenalty: Int = 0,
    ): Int {
        var score = 100
        score -= counts.getOrDefault(EventType.OVERSPEED_MINOR, 0) * 4
        score -= counts.getOrDefault(EventType.OVERSPEED_MAJOR, 0) * 9
        score -= counts.getOrDefault(EventType.HARSH_BRAKING, 0) * 7
        score -= counts.getOrDefault(EventType.HARSH_ACCELERATION, 0) * 5
        score -= counts.getOrDefault(EventType.AGGRESSIVE_CORNERING, 0) * 6
        if (gpsQuality < 0.45) score -= 5
        else if (gpsQuality < 0.65) score -= 2
        if (sensorQuality < 0.45) score -= 4
        else if (sensorQuality < 0.65) score -= 2
        if (distanceM < 150.0) score = min(score, 85)
        score -= anomalyPenalty
        return score.coerceIn(0, 100)
    }

    fun eventPenalty(events: List<DrivingEvent>): Int {
        val repeated = events.groupingBy { it.type }.eachCount()
        val excessMajor = max(0, repeated.getOrDefault(EventType.OVERSPEED_MAJOR, 0) - 2)
        val excessBrake = max(0, repeated.getOrDefault(EventType.HARSH_BRAKING, 0) - 3)
        return excessMajor + excessBrake
    }
}

data class AntiGamingAssessment(
    val flags: List<String>,
) {
    val blocked: Boolean get() = flags.isNotEmpty()
}

object TelematicsQuality {
    data class QualityResult(
        val gpsScore: Double,
        val sensorScore: Double,
        val integrityScore: Double,
        val overall: Double
    )

    fun calculate(
        gpsQuality: Double,
        sensorSamples: Long,
        elapsedMs: Long,
        suspiciousJumps: Int,
        mockLocations: Int,
        maxSpeedKmh: Double
    ): QualityResult {
        val seconds = max(1.0, elapsedMs / 1000.0)
        val sampleRate = sensorSamples / seconds
        
        val sensorScore = when {
            sampleRate >= 40.0 -> 1.0
            sampleRate >= 20.0 -> 0.85
            sampleRate >= 10.0 -> 0.65
            else -> 0.35
        }

        val jumpPenalty = min(0.4, suspiciousJumps * 0.1)
        val mockPenalty = if (mockLocations > 0) 0.9 else 0.0
        val speedPenalty = if (maxSpeedKmh > 220.0) 0.3 else 0.0
        
        val integrityScore = (1.0 - jumpPenalty - mockPenalty - speedPenalty).coerceIn(0.0, 1.0)
        
        val overall = (gpsQuality * 0.3 + sensorScore * 0.2 + integrityScore * 0.5).coerceIn(0.0, 1.0)
        
        return QualityResult(gpsQuality, sensorScore, integrityScore, overall)
    }

    // Deprecated for backward compatibility during migration
    fun score(
        gpsQuality: Double,
        sensorSamples: Long,
        elapsedMs: Long,
        eventCount: Int,
        suspiciousJumps: Int,
    ): Double = calculate(gpsQuality, sensorSamples, elapsedMs, suspiciousJumps, 0, 0.0).overall
}
