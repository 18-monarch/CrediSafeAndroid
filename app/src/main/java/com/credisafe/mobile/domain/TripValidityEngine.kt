package com.credisafe.mobile.domain

/**
 * Final gate between "we recorded something" and "this is a meaningful driving trip".
 *
 * The thresholds are deliberately conservative and centralized so they can be
 * calibrated from the real pilot dataset. A NOISE trip is hidden and scheduled
 * for automatic deletion; INVALID/REVIEW trips are retained for diagnostics but
 * never earn XP, rewards, streak progress, or cloud sync.
 */
enum class TripClassification {
    ELIGIBLE,
    NOISE,
    INVALID,
    SUSPICIOUS,
}

data class TripValidityResult(
    val classification: TripClassification,
    val eligible: Boolean,
    val reason: String,
    val shouldSync: Boolean,
    val shouldShowInHistory: Boolean,
    val discardAfterMs: Long? = null,
)

object TripValidityEngine {
    const val VERSION = "1.0"

    const val MIN_ELIGIBLE_DISTANCE_M = 500.0
    const val MIN_ELIGIBLE_DURATION_MS = 120_000L
    const val MIN_GPS_QUALITY = 0.35
    const val MIN_TELEMETRY_QUALITY = 0.35

    // "Accidental tap / stationary / tiny movement" rules.
    const val NOISE_MAX_DISTANCE_M = 200.0
    const val NOISE_MAX_DURATION_MS = 90_000L
    const val NOISE_LOW_MOVEMENT_DISTANCE_M = 300.0
    const val NOISE_MOVING_RATIO = 0.10

    const val NOISE_RETENTION_MS = 24L * 60L * 60L * 1000L

    fun assess(
        distanceM: Double,
        durationMs: Long,
        gpsQuality: Double,
        telemetryQuality: Double,
        movingLocationRatio: Double,
        locationSamples: Long,
        antiGamingFlags: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): TripValidityResult {
        val safeMovingRatio = movingLocationRatio.coerceIn(0.0, 1.0)

        val obviousNoise =
            (durationMs < NOISE_MAX_DURATION_MS && distanceM < NOISE_MAX_DISTANCE_M) ||
                (distanceM < NOISE_LOW_MOVEMENT_DISTANCE_M && safeMovingRatio < NOISE_MOVING_RATIO) ||
                (locationSamples == 0L && durationMs < NOISE_MAX_DURATION_MS)

        if (obviousNoise) {
            return TripValidityResult(
                classification = TripClassification.NOISE,
                eligible = false,
                reason = "Ignored as an accidental/noise recording: too little meaningful vehicle movement.",
                shouldSync = false,
                shouldShowInHistory = false,
                discardAfterMs = nowMs + NOISE_RETENTION_MS,
            )
        }

        val highRiskFlags = antiGamingFlags.filter {
            it.startsWith("mock_location") ||
                it.startsWith("impossible_speed") ||
                it.startsWith("suspicious_gps_jump")
        }
        if (highRiskFlags.isNotEmpty()) {
            return TripValidityResult(
                classification = TripClassification.SUSPICIOUS,
                eligible = false,
                reason = "Trip requires review because telemetry integrity checks were triggered.",
                shouldSync = false,
                shouldShowInHistory = true,
            )
        }

        val invalidReasons = buildList {
            if (distanceM < MIN_ELIGIBLE_DISTANCE_M) add("distance below 0.5 km")
            if (durationMs < MIN_ELIGIBLE_DURATION_MS) add("duration below 2 minutes")
            if (gpsQuality < MIN_GPS_QUALITY) add("GPS quality below 35%")
            if (telemetryQuality < MIN_TELEMETRY_QUALITY) add("telemetry quality below 35%")
        }

        if (invalidReasons.isNotEmpty()) {
            return TripValidityResult(
                classification = TripClassification.INVALID,
                eligible = false,
                reason = "Trip not eligible: ${invalidReasons.joinToString(", ")}.",
                shouldSync = false,
                shouldShowInHistory = true,
            )
        }

        return TripValidityResult(
            classification = TripClassification.ELIGIBLE,
            eligible = true,
            reason = "",
            shouldSync = true,
            shouldShowInHistory = true,
        )
    }
}
