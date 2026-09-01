package com.credisafe.mobile.domain

enum class RoadZoneType {
    URBAN,
    RESIDENTIAL,
    ARTERIAL,
    HIGHWAY,
    EXPRESSWAY,
    SERVICE_ROAD,
    UNKNOWN,
}

enum class RoadContextSource {
    VALHALLA_OSM_SPEED_LIMIT,
    VALHALLA_OSM,
    NONE,
}

data class RoadContext(
    val zoneType: RoadZoneType = RoadZoneType.UNKNOWN,
    val roadName: String? = null,
    val placeId: String? = null,
    val jurisdiction: String? = null,
    val speedLimitKmh: Double? = null,
    val speedLimitTrusted: Boolean = false,
    val confidence: Double = 0.0,
    val source: RoadContextSource = RoadContextSource.NONE,
    val snappedLatitude: Double? = null,
    val snappedLongitude: Double? = null,
    val providerAvailable: Boolean = false,
    val roadMatched: Boolean = false,
    val updatedAtMs: Long = 0L,
) {
    fun isFresh(nowMs: Long, maxAgeMs: Long = 45_000L): Boolean {
        if (updatedAtMs <= 0L) return false
        val age = nowMs - updatedAtMs
        return age in 0..maxAgeMs
    }

    val displayName: String
        get() = roadName?.takeIf { it.isNotBlank() }
            ?: zoneType.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

enum class OverspeedLevel { NONE, MINOR, MAJOR }

data class OverspeedDecision(
    val level: OverspeedLevel,
    val trustedLimitKmh: Double?,
    val detail: String,
)

object RoadRuleEngine {
    const val MIN_CONTEXT_CONFIDENCE = 0.70
    const val MINOR_MARGIN_KMH = 5.0
    const val MAJOR_MARGIN_KMH = 20.0

    fun overspeed(
        speedKmh: Double,
        context: RoadContext,
        nowMs: Long = System.currentTimeMillis(),
    ): OverspeedDecision {
        val limit = context.speedLimitKmh
        if (
            limit == null ||
            !context.speedLimitTrusted ||
            context.confidence < MIN_CONTEXT_CONFIDENCE ||
            !context.isFresh(nowMs)
        ) {
            return OverspeedDecision(
                OverspeedLevel.NONE,
                null,
                "No fresh trusted speed limit is available; no overspeed penalty is applied.",
            )
        }

        val excess = speedKmh - limit
        val level = when {
            excess >= MAJOR_MARGIN_KMH -> OverspeedLevel.MAJOR
            excess >= MINOR_MARGIN_KMH -> OverspeedLevel.MINOR
            else -> OverspeedLevel.NONE
        }
        val detail = when (level) {
            OverspeedLevel.MAJOR -> "Speed exceeded the trusted road limit by ${"%.1f".format(excess)} km/h."
            OverspeedLevel.MINOR -> "Speed exceeded the trusted road limit."
            OverspeedLevel.NONE -> "Speed is within the trusted road limit."
        }
        return OverspeedDecision(level, limit, detail)
    }
}

data class ZoneProfile(
    val dominantZone: RoadZoneType = RoadZoneType.UNKNOWN,
    val confidence: Double = 0.0,
    val urbanRatio: Double = 0.0,
    val highwayRatio: Double = 0.0,
    val residentialRatio: Double = 0.0,
)

class ZoneProfileAccumulator {
    private val counts = mutableMapOf<RoadZoneType, Int>()
    private var total = 0

    @Synchronized
    fun add(context: RoadContext) {
        if (!context.roadMatched || context.confidence < 0.55) return
        counts[context.zoneType] = counts.getOrDefault(context.zoneType, 0) + 1
        total++
    }

    @Synchronized
    fun reset() {
        counts.clear()
        total = 0
    }

    @Synchronized
    fun snapshot(): ZoneProfile {
        if (total == 0) return ZoneProfile()
        val dominant = counts.maxByOrNull { it.value }?.key ?: RoadZoneType.UNKNOWN
        val dominantCount = counts[dominant] ?: 0
        fun ratio(type: RoadZoneType) = (counts[type] ?: 0).toDouble() / total.toDouble()
        val highwayRatio = ratio(RoadZoneType.HIGHWAY) + ratio(RoadZoneType.EXPRESSWAY)
        val urbanRatio = ratio(RoadZoneType.URBAN) + ratio(RoadZoneType.ARTERIAL)
        return ZoneProfile(
            dominantZone = dominant,
            confidence = dominantCount.toDouble() / total.toDouble(),
            urbanRatio = urbanRatio.coerceIn(0.0, 1.0),
            highwayRatio = highwayRatio.coerceIn(0.0, 1.0),
            residentialRatio = ratio(RoadZoneType.RESIDENTIAL).coerceIn(0.0, 1.0),
        )
    }
}
