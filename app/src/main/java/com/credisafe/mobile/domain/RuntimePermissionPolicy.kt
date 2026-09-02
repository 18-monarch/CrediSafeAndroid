package com.credisafe.mobile.domain

data class RuntimePermissionGrants(
    val fineLocation: Boolean,
    val coarseLocation: Boolean,
    val activityRecognition: Boolean,
    val notifications: Boolean,
) {
    val hasLocation: Boolean
        get() = fineLocation || coarseLocation
}

enum class RuntimePermissionKind {
    FINE_LOCATION,
    COARSE_LOCATION,
    ACTIVITY_RECOGNITION,
    NOTIFICATIONS,
}

object RuntimePermissionPolicy {
    fun missing(
        sdkInt: Int,
        grants: RuntimePermissionGrants,
    ): List<RuntimePermissionKind> = buildList {
        // Android 12+ requires fine and coarse location to be requested together.
        // Once approximate location is granted, respect that user choice.
        if (!grants.hasLocation) {
            add(RuntimePermissionKind.FINE_LOCATION)
            add(RuntimePermissionKind.COARSE_LOCATION)
        }
        if (sdkInt >= 29 && !grants.activityRecognition) {
            add(RuntimePermissionKind.ACTIVITY_RECOGNITION)
        }
        if (sdkInt >= 33 && !grants.notifications) {
            add(RuntimePermissionKind.NOTIFICATIONS)
        }
    }
}
