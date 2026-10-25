package com.credisafe.mobile.domain

/**
 * Requires a trusted, fresh road limit and sustained overspeed evidence.
 * A single GPS speed peak must never create a score penalty.
 */
class SustainedOverspeedDetectorV2 {
    companion object {
        private const val REQUIRED_MS = 3000L
        private const val COOLDOWN_MS = 8000L
        private const val MAX_LOCATION_GAP_MS = 2500L
    }

    private var level: OverspeedLevel = OverspeedLevel.NONE
    private var candidateSinceMs = -1L
    private var lastSeenMs = -1L
    private var lastEmissionMs = Long.MIN_VALUE / 2
    private var candidateLimit: Double? = null

    fun reset() {
        level = OverspeedLevel.NONE
        candidateSinceMs = -1L
        lastSeenMs = -1L
        lastEmissionMs = Long.MIN_VALUE / 2
        candidateLimit = null
    }

    fun observe(
        speedKmh: Double,
        timestampMs: Long,
        context: RoadContext,
    ): OverspeedDecision {
        if (!speedKmh.isFinite() || speedKmh < 0.0 || speedKmh > TelematicsConfig.MAX_REASONABLE_SPEED_KMH ||
            timestampMs <= lastSeenMs || (lastSeenMs > 0 && timestampMs - lastSeenMs > MAX_LOCATION_GAP_MS)
        ) {
            clearCandidate()
            lastSeenMs = timestampMs
            return OverspeedDecision(OverspeedLevel.NONE, null, "Unreliable or interrupted GPS speed.")
        }
        lastSeenMs = timestampMs
        val raw = RoadRuleEngine.overspeed(speedKmh, context, timestampMs)
        if (raw.level == OverspeedLevel.NONE || raw.trustedLimitKmh == null) {
            clearCandidate()
            return raw
        }
        if (raw.level != level || candidateLimit != raw.trustedLimitKmh) {
            level = raw.level
            candidateLimit = raw.trustedLimitKmh
            candidateSinceMs = timestampMs
            return OverspeedDecision(OverspeedLevel.NONE, raw.trustedLimitKmh, "Speed exceedance not yet sustained.")
        }

        if (timestampMs - candidateSinceMs < REQUIRED_MS ||
            timestampMs - lastEmissionMs < COOLDOWN_MS
        ) {
            return OverspeedDecision(OverspeedLevel.NONE, raw.trustedLimitKmh, "Waiting for sustained trusted speed.")
        }
        lastEmissionMs = timestampMs
        return raw
    }

    private fun clearCandidate() {
        level = OverspeedLevel.NONE
        candidateSinceMs = -1L
        candidateLimit = null
    }
}
