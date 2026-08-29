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
    GOOGLE_ROADS_SPEED_LIMIT,
    GOOGLE_ROADS_GEOCODE,
    GOOGLE_ROADS,
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
    val updatedAtMs: Long = 0L,
) {
    fun isFresh(nowMs: Long, maxAgeMs: Long = 45_000L): Boolean =
        updatedAtMs > 0L && nowMs - updatedAtMs <= maxAgeMs

    val displayName: String
        get() = roadName?.takeIf { it.isNotBlank() }
            ?: zoneType.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

enum class OverspeedLevel {
    NONE,
    MINOR,
    MAJOR,
}

data class OverspeedDecision(
    val level: OverspeedLevel,
    val trustedLimitKmh: Double?,
    val detail: String,
)

/**
 * Road rules intentionally penalize only against a fresh, trusted road speed
 * limit. If the provider cannot supply a reliable limit, CrediSafe does NOT
 * invent a city/highway limit and does NOT issue an overspeed event.
 */
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
                level = OverspeedLevel.NONE,
                trustedLimitKmh = null,
                detail = "No fresh trusted speed limit is available; no overspeed penalty is applied.",
            )
        }

        val excess = speedKmh - limit
        val level = when {
            excess >= MAJOR_MARGIN_KMH -> OverspeedLevel.MAJOR
            excess >= MINOR_MARGIN_KMH -> OverspeedLevel.MINOR
            else -> OverspeedLevel.NONE
        }

        val detail = when (level) {
            OverspeedLevel.MAJOR ->
                "Speed ${"%.1f".format(speedKmh)} km/h exceeded the trusted ${"%.0f".format(limit)} km/h road limit by ${"%.1f".format(excess)} km/h."
            OverspeedLevel.MINOR ->
                "Speed ${"%.1f".format(speedKmh)} km/h exceeded the trusted ${"%.0f".format(limit)} km/h road limit."
            OverspeedLevel.NONE ->
                "Speed is within the trusted road limit."
        }
        return OverspeedDecision(level, limit, detail)
    }
}
