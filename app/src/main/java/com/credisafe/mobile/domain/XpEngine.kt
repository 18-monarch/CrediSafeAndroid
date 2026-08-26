package com.credisafe.mobile.domain

import com.credisafe.mobile.data.DrivingEvent
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
    val eligible: Boolean,
    val rewardEligible: Boolean,
    val subtotal: Int,
    val total: Int,
    val rewardPoints: Int,
    val items: List<XpItem>,
    val note: String,
    val eligibilityReason: String,
    val antiGamingFlags: List<String>,
)

object XpEngine {
    const val VERSION = "2.1"
    const val TRIP_CAP = 220
    const val MIN_DISTANCE_M = 500.0
    const val MIN_DURATION_MS = 120_000L
    const val MIN_GPS_QUALITY = 0.35
    const val DISTANCE_CAP = 50
    const val CLEAN_BONUS = 25

    fun calculate(
        mode: TripMode,
        safetyScore: Int,
        distanceM: Double,
        durationMs: Long,
        gpsQuality: Double,
        events: List<DrivingEvent>,
        streakDays: Int,
        firstTripOfDay: Boolean,
        antiGamingFlags: List<String>,
    ): XpResult {
        val problems = mutableListOf<String>()
        if (mode == TripMode.REAL_GPS && distanceM < MIN_DISTANCE_M) {
            problems += "Trip covered less than 0.5 km."
        }
        if (mode == TripMode.REAL_GPS && durationMs < MIN_DURATION_MS) {
            problems += "Trip lasted less than 2 minutes."
        }
        if (mode == TripMode.REAL_GPS && gpsQuality < MIN_GPS_QUALITY) {
            problems += "GPS confidence is below 35%."
        }
        if (antiGamingFlags.isNotEmpty()) {
            problems += "Trip contains telemetry quality/anomaly flags."
        }

        if (problems.isNotEmpty()) {
            return XpResult(
                engineVersion = VERSION,
                eligible = false,
                rewardEligible = false,
                subtotal = 0,
                total = 0,
                rewardPoints = 0,
                items = listOf(XpItem("eligibility", "Trip eligibility", 0, problems.joinToString(" "))),
                note = "Trip retained for review; no XP, reward points or streak progress are issued.",
                eligibilityReason = problems.joinToString(" "),
                antiGamingFlags = antiGamingFlags,
            )
        }

        val items = mutableListOf<XpItem>()
        items += XpItem("completion", "Valid trip completion", 25, "Awarded for completing an eligible journey.")

        val safeScore = safetyXp(safetyScore)
        items += XpItem(
            "safety",
            "Safety score $safetyScore/100",
            safeScore,
            "Safety performance is the largest component of trip XP.",
        )

        val distanceXp = min(DISTANCE_CAP, floor(max(0.0, distanceM) / 1000.0 * 2.0).toInt())
        if (distanceXp > 0) {
            items += XpItem("distance", "Validated distance", distanceXp, "2 XP per kilometre, capped at 50 XP per trip.")
        }

        val overspeedCount = events.count { it.type == com.credisafe.mobile.data.EventType.OVERSPEED_MINOR || it.type == com.credisafe.mobile.data.EventType.OVERSPEED_MAJOR }
        if (overspeedCount == 0 && safetyScore >= 90) {
            items += XpItem("clean_trip", "Clean-trip bonus", CLEAN_BONUS, "No overspeed events and safety score remained at or above 90.")
        }

        if (gpsQuality >= 0.90) {
            items += XpItem("gps_quality", "Trusted GPS data", 15, "GPS confidence was at least 90%.")
        } else if (gpsQuality >= 0.75) {
            items += XpItem("gps_quality", "Good GPS data", 8, "GPS confidence was at least 75%.")
        }

        if (firstTripOfDay && streakDays >= 2) {
            val streakXp = min(25, (streakDays - 1) * 5)
            items += XpItem("streak", "$streakDays-day safe-driving streak", streakXp, "Streak XP is awarded once per day.")
        }

        val subtotal = items.sumOf { it.points }
        val total = min(TRIP_CAP, subtotal)
        if (subtotal > total) {
            items += XpItem(
                "cap",
                "Per-trip XP cap",
                total - subtotal,
                "CrediSafe caps a single eligible trip at 220 XP.",
            )
        }

        return XpResult(
            engineVersion = VERSION,
            eligible = true,
            rewardEligible = true,
            subtotal = subtotal,
            total = total,
            rewardPoints = total / 2,
            items = items,
            note = "Lifetime XP remains separate from spendable reward points. Real trips convert 50% of awarded XP into reward points.",
            eligibilityReason = "",
            antiGamingFlags = antiGamingFlags,
        )
    }

    private fun safetyXp(score: Int): Int = when {
        score >= 95 -> 110
        score >= 90 -> 90
        score >= 80 -> 60
        score >= 70 -> 40
        score >= 50 -> 20
        else -> 5
    }

    fun level(totalXp: Int): String = when {
        totalXp >= 5500 -> "Legend"
        totalXp >= 3500 -> "Elite"
        totalXp >= 2000 -> "Platinum"
        totalXp >= 1000 -> "Gold"
        totalXp >= 500 -> "Silver"
        else -> "Bronze"
    }

    data class LevelProgress(
        val current: String,
        val next: String?,
        val progress: Float,
        val remaining: Int
    )

    fun calculateProgress(totalXp: Int): LevelProgress {
        val levels = listOf(
            "Bronze" to 0,
            "Silver" to 500,
            "Gold" to 1000,
            "Platinum" to 2000,
            "Elite" to 3500,
            "Legend" to 5500
        )
        
        var currentLevel = levels[0]
        var nextLevel: Pair<String, Int>? = null
        
        for (i in levels.indices) {
            if (totalXp >= levels[i].second) {
                currentLevel = levels[i]
                nextLevel = levels.getOrNull(i + 1)
            } else {
                break
            }
        }
        
        return if (nextLevel != null) {
            val range = nextLevel.second - currentLevel.second
            val gained = totalXp - currentLevel.second
            LevelProgress(
                currentLevel.first,
                nextLevel.first,
                (gained.toFloat() / range).coerceIn(0f, 1f),
                nextLevel.second - totalXp
            )
        } else {
            LevelProgress(currentLevel.first, null, 1f, 0)
        }
    }
}
