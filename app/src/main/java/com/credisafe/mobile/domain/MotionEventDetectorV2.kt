package com.credisafe.mobile.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Pilot motion detector. Its thresholds are hypotheses, not measured crash risk.
 * Uses a monotonic sensor timestamp, smoothing, dwell time and hysteresis so a
 * single phone bump cannot create a driving offence.
 */
enum class MotionKind { HARSH_BRAKING, HARSH_ACCELERATION, AGGRESSIVE_CORNERING }

data class MotionDetection(
    val kind: MotionKind,
    val confidence: Double,
    val smoothedAcceleration: Double,
    val evidenceMs: Long,
)

class MotionEventDetectorV2 {
    companion object {
        const val VERSION = "MOTION_RULES_V2_PILOT"
        private const val TIME_CONSTANT_MS = 180.0
        private const val CONFIRM_MS = 350L
        private const val MAX_GAP_MS = 500L
        private const val COOLDOWN_MS = 8000L
    }

    private data class Candidate(
        var sinceMs: Long = -1L,
        var lastConfirmedMs: Long = Long.MIN_VALUE / 2,
        var peak: Double = 0.0,
    )

    private val states = MotionKind.entries.associateWith { Candidate() }
    private var lastMs = -1L
    private var smoothedLong = 0.0
    private var smoothedLat = 0.0
    private var primed = false

    fun reset() {
        states.values.forEach {
            it.sinceMs = -1L
            it.lastConfirmedMs = Long.MIN_VALUE / 2
            it.peak = 0.0
        }
        lastMs = -1L
        smoothedLong = 0.0
        smoothedLat = 0.0
        primed = false
    }

    fun observe(
        timestampMs: Long,
        longitudinalMps2: Double,
        lateralMps2: Double,
        speedKmh: Double,
        reliableOrientationAndGps: Boolean,
    ): List<MotionDetection> {
        if (!longitudinalMps2.isFinite() || !lateralMps2.isFinite() ||
            !speedKmh.isFinite() || !reliableOrientationAndGps ||
            timestampMs < 0 || (lastMs >= 0 && timestampMs <= lastMs)
        ) {
            clearCandidates()
            primed = false
            return emptyList()
        }

        val dt = if (lastMs >= 0) timestampMs - lastMs else 0L
        if (dt > MAX_GAP_MS) {
            clearCandidates()
            primed = false
        }
        lastMs = timestampMs

        if (!primed) {
            // Do not let the first potentially displaced phone reading trigger an event.
            smoothedLong = 0.0
            smoothedLat = 0.0
            primed = true
            return emptyList()
        }

        val alpha = 1.0 - exp(-dt.coerceAtLeast(1L) / TIME_CONSTANT_MS)
        smoothedLong += alpha * (longitudinalMps2 - smoothedLong)
        smoothedLat += alpha * (lateralMps2 - smoothedLat)

        val results = mutableListOf<MotionDetection>()
        fun detect(kind: MotionKind, magnitude: Double, threshold: Double, speedValid: Boolean) {
            val state = states.getValue(kind)
            // Lower release threshold avoids chattering at the trigger boundary.
            val release = threshold * 0.7
            if (!speedValid || magnitude < release) {
                state.sinceMs = -1L
                state.peak = 0.0
                return
            }
            if (magnitude >= threshold && state.sinceMs < 0) state.sinceMs = timestampMs
            if (state.sinceMs < 0) return
            state.peak = max(state.peak, magnitude)
            val dwell = timestampMs - state.sinceMs
            if (dwell >= CONFIRM_MS && timestampMs - state.lastConfirmedMs >= COOLDOWN_MS) {
                results += MotionDetection(
                    kind = kind,
                    confidence = (0.65 + ((state.peak - threshold) / max(1.0, threshold)) * 0.25)
                        .coerceIn(0.65, 0.95),
                    smoothedAcceleration = state.peak,
                    evidenceMs = dwell,
                )
                state.lastConfirmedMs = timestampMs
                state.sinceMs = -1L
                state.peak = 0.0
            }
        }

        detect(MotionKind.HARSH_BRAKING, -smoothedLong,
            abs(TelematicsConfig.HARSH_BRAKING_MPS2), speedKmh >= TelematicsConfig.MIN_MOVING_SPEED_KMH)
        detect(MotionKind.HARSH_ACCELERATION, smoothedLong,
            TelematicsConfig.HARSH_ACCELERATION_MPS2, speedKmh >= TelematicsConfig.MIN_MOVING_SPEED_KMH)
        detect(MotionKind.AGGRESSIVE_CORNERING, abs(smoothedLat),
            TelematicsConfig.AGGRESSIVE_LATERAL_MPS2, speedKmh >= TelematicsConfig.MIN_CORNER_SPEED_KMH)
        return results
    }

    private fun clearCandidates() {
        states.values.forEach {
            it.sinceMs = -1L
            it.peak = 0.0
        }
    }
}
