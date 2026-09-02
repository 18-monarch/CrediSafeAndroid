package com.credisafe.mobile

import com.credisafe.mobile.domain.RuntimePermissionGrants
import com.credisafe.mobile.domain.RuntimePermissionKind
import com.credisafe.mobile.domain.RuntimePermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePermissionPolicyTest {
    @Test
    fun cleanInstallRequestsLocationTogetherOnAndroid28() {
        assertEquals(
            listOf(
                RuntimePermissionKind.FINE_LOCATION,
                RuntimePermissionKind.COARSE_LOCATION,
            ),
            RuntimePermissionPolicy.missing(28, grants()),
        )
    }

    @Test
    fun cleanInstallAlsoRequestsActivityRecognitionOnAndroid29() {
        assertEquals(
            listOf(
                RuntimePermissionKind.FINE_LOCATION,
                RuntimePermissionKind.COARSE_LOCATION,
                RuntimePermissionKind.ACTIVITY_RECOGNITION,
            ),
            RuntimePermissionPolicy.missing(29, grants()),
        )
    }

    @Test
    fun cleanInstallRequestsAllRelevantRuntimePermissionsOnAndroid33() {
        assertEquals(
            listOf(
                RuntimePermissionKind.FINE_LOCATION,
                RuntimePermissionKind.COARSE_LOCATION,
                RuntimePermissionKind.ACTIVITY_RECOGNITION,
                RuntimePermissionKind.NOTIFICATIONS,
            ),
            RuntimePermissionPolicy.missing(33, grants()),
        )
    }

    @Test
    fun approximateLocationIsAcceptedWithoutRepeatedFineLocationPrompt() {
        assertEquals(
            listOf(
                RuntimePermissionKind.ACTIVITY_RECOGNITION,
                RuntimePermissionKind.NOTIFICATIONS,
            ),
            RuntimePermissionPolicy.missing(
                33,
                grants(coarseLocation = true),
            ),
        )
    }

    @Test
    fun fullyGrantedDeviceNeedsNoMorePrompts() {
        assertEquals(
            emptyList<RuntimePermissionKind>(),
            RuntimePermissionPolicy.missing(
                37,
                grants(
                    fineLocation = true,
                    activityRecognition = true,
                    notifications = true,
                ),
            ),
        )
    }

    private fun grants(
        fineLocation: Boolean = false,
        coarseLocation: Boolean = false,
        activityRecognition: Boolean = false,
        notifications: Boolean = false,
    ) = RuntimePermissionGrants(
        fineLocation = fineLocation,
        coarseLocation = coarseLocation,
        activityRecognition = activityRecognition,
        notifications = notifications,
    )
}
