package com.credisafe.mobile.domain

enum class RecognizedActivity {
    IN_VEHICLE,
    ON_BICYCLE,
    WALKING,
    RUNNING,
    STILL,
    ON_FOOT,
    UNKNOWN,
}

enum class TransportMode {
    DRIVING,
    ROAD_VEHICLE,
    WALKING,
    RUNNING,
    BICYCLE,
    STILL,
    POSSIBLE_RAIL_TRANSIT,
    UNKNOWN,
}

data class MobilitySnapshot(
    val activity: RecognizedActivity = RecognizedActivity.UNKNOWN,
    val confidence: Int = 0,
    val updatedAtMs: Long = 0L,
) {
    fun isFresh(nowMs: Long, maxAgeMs: Long = 90_000L): Boolean {
        if (updatedAtMs <= 0L) return false
        val age = nowMs - updatedAtMs
        return age in 0..maxAgeMs
    }
}

data class MobilityDecision(
    val mode: TransportMode,
    val confidence: Int,
    val drivingEligible: Boolean,
    val reason: String,
) {
    companion object {
        fun unknown() = MobilityDecision(
            mode = TransportMode.UNKNOWN,
            confidence = 0,
            drivingEligible = true,
            reason = "Mobility context unavailable; trip eligibility relies on telemetry and road evidence.",
        )
    }
}

object MobilityEngine {
    const val NON_DRIVING_BLOCK_CONFIDENCE = 75
    const val TRANSIT_REVIEW_CONFIDENCE = 80

    fun decide(
        dominantActivity: RecognizedActivity,
        activityConfidence: Int,
        roadProviderSamples: Int,
        roadMatchedSamples: Int,
        avgSpeedKmh: Double,
        maxSpeedKmh: Double,
    ): MobilityDecision {
        val confidence = activityConfidence.coerceIn(0, 100)
        when (dominantActivity) {
            RecognizedActivity.WALKING,
            RecognizedActivity.ON_FOOT -> if (confidence >= NON_DRIVING_BLOCK_CONFIDENCE) {
                return MobilityDecision(TransportMode.WALKING, confidence, false, "High-confidence walking/on-foot activity.")
            }
            RecognizedActivity.RUNNING -> if (confidence >= NON_DRIVING_BLOCK_CONFIDENCE) {
                return MobilityDecision(TransportMode.RUNNING, confidence, false, "High-confidence running activity.")
            }
            RecognizedActivity.ON_BICYCLE -> if (confidence >= NON_DRIVING_BLOCK_CONFIDENCE) {
                return MobilityDecision(TransportMode.BICYCLE, confidence, false, "High-confidence bicycle activity.")
            }
            RecognizedActivity.STILL -> if (confidence >= 85 && avgSpeedKmh < 3.0) {
                return MobilityDecision(TransportMode.STILL, confidence, false, "Device remained still for most of the recording.")
            }
            else -> Unit
        }

        if (dominantActivity == RecognizedActivity.IN_VEHICLE && confidence >= 60) {
            val roadMatchRatio = if (roadProviderSamples > 0) {
                roadMatchedSamples.toDouble() / roadProviderSamples.toDouble()
            } else 0.0

            // Conservative rail/transit heuristic: only when the road provider
            // was actually available enough times and the journey repeatedly
            // failed to match roads while moving at vehicle speed.
            if (
                roadProviderSamples >= 5 &&
                roadMatchRatio < 0.20 &&
                avgSpeedKmh >= 25.0 &&
                maxSpeedKmh >= 40.0
            ) {
                val railConfidence = minOf(95, 70 + ((1.0 - roadMatchRatio) * 25.0).toInt())
                return MobilityDecision(
                    TransportMode.POSSIBLE_RAIL_TRANSIT,
                    railConfidence,
                    false,
                    "In-vehicle activity was detected, but movement consistently failed to match the road network.",
                )
            }

            if (roadMatchedSamples > 0) {
                val drivingConfidence = minOf(99, maxOf(confidence, 70 + (roadMatchRatio * 25.0).toInt()))
                return MobilityDecision(
                    TransportMode.DRIVING,
                    drivingConfidence,
                    true,
                    "In-vehicle activity and road-network evidence agree.",
                )
            }

            return MobilityDecision(
                TransportMode.ROAD_VEHICLE,
                confidence,
                true,
                "In-vehicle activity detected. Road evidence is unavailable or inconclusive.",
            )
        }

        return MobilityDecision.unknown()
    }
}

class MobilityAccumulator {
    private val activityCounts = mutableMapOf<RecognizedActivity, Int>()
    private val confidenceSums = mutableMapOf<RecognizedActivity, Int>()
    private var samples = 0

    fun add(snapshot: MobilitySnapshot, nowMs: Long = System.currentTimeMillis()) {
        if (!snapshot.isFresh(nowMs)) return
        activityCounts[snapshot.activity] = activityCounts.getOrDefault(snapshot.activity, 0) + 1
        confidenceSums[snapshot.activity] = confidenceSums.getOrDefault(snapshot.activity, 0) + snapshot.confidence
        samples++
    }

    fun reset() {
        activityCounts.clear()
        confidenceSums.clear()
        samples = 0
    }

    fun dominant(): Pair<RecognizedActivity, Int> {
        if (samples == 0) return RecognizedActivity.UNKNOWN to 0
        val activity = activityCounts.maxByOrNull { it.value }?.key ?: RecognizedActivity.UNKNOWN
        val count = activityCounts[activity] ?: 1
        val avgConfidence = (confidenceSums[activity] ?: 0) / count
        return activity to avgConfidence.coerceIn(0, 100)
    }
}
