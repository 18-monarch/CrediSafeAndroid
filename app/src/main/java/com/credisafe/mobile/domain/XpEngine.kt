package com.credisafe.mobile.domain

import com.credisafe.mobile.data.DrivingEvent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class TripMode {
    REAL_GPS
}

data class XpItem(
    val code: String,
    val label: String,
    val points: Int,
    val detail: String,
)

data class XpResult(
    val engineVersion: String,
    val status: String = "ESTIMATED_PREVIEW",
    val eligible: Boolean,
    val rewardEligible: Boolean,
    val subtotal: Int,
    val total: Int,
    val rewardPoints: Int,
    val items: List<XpItem>,
    val note: String,
    val eligibilityReason: String,
    val antiGamingFlags: List<String>,
    val reasonCodes: List<String> = emptyList(),
)

object XpEngine {
    const val VERSION = "2.7"
    const val RULESET_VERSION = "XP_RULESET_V1"

    object Config {
        const val PER_TRIP_XP_CAP_NOTE = "Per-trip XP cap"
        const val COMPLETION_BASE_XP = 8.0
        const val MAX_SAFETY_BONUS_XP = 22.0
        const val MIN_SAFETY_SCORE_FOR_BONUS = 50.0
        const val SAFETY_SCORE_RANGE = 45.0
        const val EXPOSURE_BENCHMARK_MINUTES = 20.0
        const val MIN_EXPOSURE_FACTOR = 0.75
        const val MAX_EXPOSURE_FACTOR = 1.25
        const val MIN_QUALITY_FACTOR = 0.70
        const val MAX_QUALITY_FACTOR = 1.00
        const val MIN_CONFIRMED_TRIP_XP = 5
        const val MAX_CONFIRMED_TRIP_XP = 38

        const val MIN_ELIGIBLE_DISTANCE_M = 500.0
        const val MIN_ELIGIBLE_DURATION_MS = 120_000L
        const val MIN_GPS_QUALITY = 0.35
        const val MIN_TELEMETRY_QUALITY = 0.35

        const val DAILY_XP_CAP = 100
        const val WEEKLY_XP_CAP = 450
        const val MAX_DAILY_COMPLETION_AWARDS = 5
    }

    fun totalXpRequiredForLevel(level: Int): Int {
        if (level <= 1) return 0
        return (100.0 * (level - 1).toDouble().pow(1.5)).roundToInt()
    }

    fun level(totalXp: Int): String {
        val info = calculateLevelInfo(totalXp)
        return "Level ${info.currentLevel}"
    }

    data class LevelInfo(
        val currentLevel: Int,
        val currentLevelStartingXp: Int,
        val nextLevelRequiredXp: Int,
        val xpEarnedInCurrentLevel: Int,
        val xpRemaining: Int,
        val progressPercent: Float,
    )

    fun calculateLevelInfo(totalXp: Int): LevelInfo {
        val safeTotal = max(0, totalXp)
        var level = 1
        while (totalXpRequiredForLevel(level + 1) <= safeTotal) {
            level++
            if (level >= 1000) break
        }
        val startingXp = totalXpRequiredForLevel(level)
        val requiredXp = totalXpRequiredForLevel(level + 1)
        val range = max(1, requiredXp - startingXp)
        val earned = safeTotal - startingXp
        val remaining = max(0, requiredXp - safeTotal)
        val percent = (earned.toFloat() / range.toFloat()).coerceIn(0f, 1f)

        return LevelInfo(
            currentLevel = level,
            currentLevelStartingXp = startingXp,
            nextLevelRequiredXp = requiredXp,
            xpEarnedInCurrentLevel = earned,
            xpRemaining = remaining,
            progressPercent = percent,
        )
    }

    data class LevelProgress(
        val currentLevel: Int,
        val currentLevelLabel: String,
        val nextLevelLabel: String?,
        val currentLevelStartingXp: Int,
        val nextLevelRequiredXp: Int,
        val progress: Float,
        val remaining: Int,
        val current: String,
        val next: String?,
    )

    fun calculateProgress(totalXp: Int): LevelProgress {
        val info = calculateLevelInfo(totalXp)
        return LevelProgress(
            currentLevel = info.currentLevel,
            currentLevelLabel = "Level ${info.currentLevel}",
            nextLevelLabel = "Level ${info.currentLevel + 1}",
            currentLevelStartingXp = info.currentLevelStartingXp,
            nextLevelRequiredXp = info.nextLevelRequiredXp,
            progress = info.progressPercent,
            remaining = info.xpRemaining,
            current = "Level ${info.currentLevel}",
            next = "Level ${info.currentLevel + 1}",
        )
    }

    fun calculate(
        mode: TripMode = TripMode.REAL_GPS,
        safetyScore: Int,
        distanceM: Double,
        durationMs: Long,
        gpsQuality: Double,
        events: List<DrivingEvent> = emptyList(),
        streakDays: Int = 1,
        firstTripOfDay: Boolean = false,
        antiGamingFlags: List<String> = emptyList(),
        mobility: MobilityDecision = MobilityDecision.unknown(),
        zoneProfile: ZoneProfile = ZoneProfile(),
        telemetryQuality: Double = gpsQuality,
    ): XpResult {
        val reasons = mutableListOf<String>()
        val codes = mutableListOf<String>()

        if (mode == TripMode.REAL_GPS && distanceM < Config.MIN_ELIGIBLE_DISTANCE_M) {
            codes += "INSUFFICIENT_DISTANCE"
            reasons += "Trip distance below 0.5 km."
        }
        if (mode == TripMode.REAL_GPS && durationMs < Config.MIN_ELIGIBLE_DURATION_MS) {
            codes += "INSUFFICIENT_DURATION"
            reasons += "Trip duration below 2 minutes."
        }
        if (mode == TripMode.REAL_GPS && gpsQuality < Config.MIN_GPS_QUALITY) {
            codes += "LOW_TELEMETRY_QUALITY"
            reasons += "GPS confidence below 35%."
        }
        if (telemetryQuality < Config.MIN_TELEMETRY_QUALITY) {
            if (!codes.contains("LOW_TELEMETRY_QUALITY")) codes += "LOW_TELEMETRY_QUALITY"
            reasons += "Telemetry quality below 35%."
        }
        if (antiGamingFlags.isNotEmpty()) {
            codes += "ANOMALOUS_TELEMETRY"
            reasons += "Trip contains telemetry quality/anomaly flags."
        }

        if (!mobility.drivingEligible && mobility.confidence >= MobilityEngine.NON_DRIVING_BLOCK_CONFIDENCE) {
            val code = when (mobility.mode) {
                TransportMode.WALKING -> "WALKING_TRIP"
                TransportMode.RUNNING -> "RUNNING_TRIP"
                TransportMode.BICYCLE -> "BICYCLE_TRIP"
                TransportMode.STILL -> "STILL_SESSION"
                TransportMode.POSSIBLE_RAIL_TRANSIT -> "POSSIBLE_RAIL_TRANSIT"
                else -> "NON_DRIVING_TRIP"
            }
            codes += code
            reasons += "Mobility context indicates ${mobility.mode.name.lowercase().replace('_', ' ')}."
        }

        if (reasons.isNotEmpty()) {
            return XpResult(
                engineVersion = VERSION,
                status = "ESTIMATED_INELIGIBLE",
                eligible = false,
                rewardEligible = false,
                subtotal = 0,
                total = 0,
                rewardPoints = 0,
                items = listOf(XpItem("ineligible", "Ineligible Trip", 0, reasons.joinToString(" "))),
                note = "Estimated local preview: trip is not eligible for driving XP. Final determination is performed by CrediSafe server.",
                eligibilityReason = reasons.joinToString(" "),
                antiGamingFlags = antiGamingFlags,
                reasonCodes = codes,
            )
        }

        // --- ESTIMATED LOCAL PREVIEW CALCULATION (XP_RULESET_V1) ---
        val items = mutableListOf<XpItem>()

        val basePoints = Config.COMPLETION_BASE_XP.toInt()
        items += XpItem("completion_base", "Eligible trip completion base", basePoints, "Base award for an eligible trip.")

        val safeNormalized = ((safetyScore.toDouble() - Config.MIN_SAFETY_SCORE_FOR_BONUS) / Config.SAFETY_SCORE_RANGE).coerceIn(0.0, 1.0)
        val safetyBonus = (Config.MAX_SAFETY_BONUS_XP * safeNormalized).roundToInt()
        if (safetyBonus > 0) {
            items += XpItem("safety_bonus", "Safe driving bonus ($safetyScore/100)", safetyBonus, "Calculated from estimated trip safety score.")
        }

        val activeMinutes = max(0.0, durationMs / 60000.0)
        val exposureFactor = (sqrt(activeMinutes / Config.EXPOSURE_BENCHMARK_MINUTES)).coerceIn(Config.MIN_EXPOSURE_FACTOR, Config.MAX_EXPOSURE_FACTOR)
        val qualityFactor = telemetryQuality.coerceIn(Config.MIN_QUALITY_FACTOR, Config.MAX_QUALITY_FACTOR)

        val rawXp = ((Config.COMPLETION_BASE_XP + (Config.MAX_SAFETY_BONUS_XP * safeNormalized)) * exposureFactor * qualityFactor).roundToInt()
        val estimatedTotal = rawXp.coerceIn(Config.MIN_CONFIRMED_TRIP_XP, Config.MAX_CONFIRMED_TRIP_XP)

        codes += "ELIGIBLE_TRIP"
        if (safetyScore >= 70) codes += "SAFE_DRIVING_BONUS"

        return XpResult(
            engineVersion = VERSION,
            status = "ESTIMATED_PREVIEW",
            eligible = true,
            rewardEligible = true,
            subtotal = rawXp,
            total = estimatedTotal,
            rewardPoints = estimatedTotal / 2,
            items = items,
            note = "Estimated local preview. Confirmed XP is issued exclusively by the CrediSafe server after telemetry verification.",
            eligibilityReason = "",
            antiGamingFlags = antiGamingFlags,
            reasonCodes = codes,
        )
    }
}
