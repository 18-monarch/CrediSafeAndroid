package com.credisafe.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.credisafe.mobile.data.MobilityContextStore
import com.credisafe.mobile.domain.MobilitySnapshot
import com.credisafe.mobile.domain.RecognizedActivity
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

class MobilityActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val mostLikely = result.probableActivities.maxByOrNull { it.confidence } ?: return

        MobilityContextStore.set(
            MobilitySnapshot(
                activity = mostLikely.toCrediSafeActivity(),
                confidence = mostLikely.confidence.coerceIn(0, 100),
                updatedAtMs = result.time,
            ),
        )
    }

    private fun DetectedActivity.toCrediSafeActivity(): RecognizedActivity = when (type) {
        DetectedActivity.IN_VEHICLE -> RecognizedActivity.IN_VEHICLE
        DetectedActivity.ON_BICYCLE -> RecognizedActivity.ON_BICYCLE
        DetectedActivity.WALKING -> RecognizedActivity.WALKING
        DetectedActivity.RUNNING -> RecognizedActivity.RUNNING
        DetectedActivity.STILL -> RecognizedActivity.STILL
        DetectedActivity.ON_FOOT -> RecognizedActivity.ON_FOOT
        else -> RecognizedActivity.UNKNOWN
    }
}
