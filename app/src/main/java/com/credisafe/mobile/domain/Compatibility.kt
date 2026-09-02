package com.credisafe.mobile.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

object CompatibilityChecker {
    fun check(context: Context): CompatibilityState {
        val issues = mutableListOf<CompatibilityIssue>()
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val hasAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val hasLinearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
        val hasRotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

        if (!hasAccel) {
            issues += CompatibilityIssue(
                IssueLevel.CRITICAL,
                "Accelerometer unavailable",
                "This device can install CrediSafe, but reliable driving telemetry requires an accelerometer.",
            )
        }

        if (!hasGyro || !hasLinearAccel || !hasRotation) {
            issues += CompatibilityIssue(
                IssueLevel.WARNING,
                "Reduced motion-sensor capability",
                "One or more advanced motion sensors are unavailable. Trips can still record, but telemetry precision may be reduced.",
            )
        }

        val playServicesStatus =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServicesStatus != ConnectionResult.SUCCESS) {
            issues += CompatibilityIssue(
                IssueLevel.CRITICAL,
                "Google Play services unavailable",
                "This beta uses Google Play services only for fused location and activity recognition. The map itself uses MapLibre.",
            )
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            issues += CompatibilityIssue(
                IssueLevel.INFO,
                "Mobility recognition permission not granted",
                "CrediSafe can still record trips, but walking/running/cycling filtering will be less reliable until Physical Activity permission is granted.",
            )
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val optimizationActive = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        if (optimizationActive) {
            issues += CompatibilityIssue(
                IssueLevel.WARNING,
                "Battery optimization active",
                "Some Android vendors may restrict long journeys. If trips stop in the background, allow CrediSafe unrestricted battery use.",
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            )
        }

        val locationMode = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF,
        )
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (locationMode == Settings.Secure.LOCATION_MODE_OFF) {
            issues += CompatibilityIssue(
                IssueLevel.CRITICAL,
                "Location services off",
                "Enable device location before starting a CrediSafe journey.",
                Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            )
        } else if (!hasLocationPermission) {
            issues += CompatibilityIssue(
                IssueLevel.INFO,
                "Location permission not granted",
                "Tap Continue or Start journey and CrediSafe will request location access in the app.",
            )
        }

        return CompatibilityState(
            sensorsReady = hasAccel,
            locationReady = locationMode != Settings.Secure.LOCATION_MODE_OFF && hasLocationPermission,
            batteryOptimized = optimizationActive,
            issues = issues,
        )
    }
}
