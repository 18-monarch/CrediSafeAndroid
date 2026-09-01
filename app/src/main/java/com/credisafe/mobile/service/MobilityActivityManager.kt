package com.credisafe.mobile.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.credisafe.mobile.data.MobilityContextStore
import com.google.android.gms.location.ActivityRecognition

class MobilityActivityManager(private val context: Context) {
    private val client = ActivityRecognition.getClient(context.applicationContext)
    @Volatile private var requested = false

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, MobilityActivityReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (requested) return
        if (!hasPermission()) {
            MobilityContextStore.reset()
            return
        }
        requested = true
        runCatching {
            client.requestActivityUpdates(ACTIVITY_INTERVAL_MS, pendingIntent)
                .addOnFailureListener { requested = false }
        }.onFailure {
            requested = false
        }
    }

    fun stop() {
        if (!requested) return
        runCatching {
            client.removeActivityUpdates(pendingIntent)
        }
        requested = false
    }

    companion object {
        private const val REQUEST_CODE = 2601
        private const val ACTIVITY_INTERVAL_MS = 5_000L
    }
}
