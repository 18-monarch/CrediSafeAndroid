package com.credisafe.mobile.domain

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object CompatibilityChecker {
    fun check(context: Context): CompatibilityState {
        val issues = mutableListOf<CompatibilityIssue>()
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        // 1. Check Mandatory Sensors
        val hasAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasLinearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
        val hasRotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        
        if (!hasAccel) {
            issues.add(CompatibilityIssue(
                IssueLevel.CRITICAL,
                "Accelerometer Missing",
                "Your device lacks a basic accelerometer. Telemetry cannot function."
            ))
        }
        
        if (!hasLinearAccel || !hasRotation) {
            issues.add(CompatibilityIssue(
                IssueLevel.WARNING,
                "Advanced Sensors Missing",
                "Device lacks linear acceleration or rotation sensors. Precision may be reduced."
            ))
        }

        // 2. Check Battery Optimization (Doze Mode)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        if (isOptimized) {
            issues.add(CompatibilityIssue(
                IssueLevel.WARNING,
                "Battery Optimization Active",
                "System may kill the app during long trips. Disable optimization for CrediSafe.",
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ))
        }

        // 3. Check Location Settings
        val locationMode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
        if (locationMode == Settings.Secure.LOCATION_MODE_OFF) {
            issues.add(CompatibilityIssue(
                IssueLevel.CRITICAL,
                "Location Services Off",
                "Please enable high-accuracy location in system settings.",
                Settings.ACTION_LOCATION_SOURCE_SETTINGS
            ))
        }

        return CompatibilityState(
            sensorsReady = hasAccel,
            locationReady = locationMode != Settings.Secure.LOCATION_MODE_OFF,
            batteryOptimized = isOptimized,
            issues = issues
        )
    }
}
