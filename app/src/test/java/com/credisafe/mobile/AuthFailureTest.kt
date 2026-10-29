package com.credisafe.mobile

import com.credisafe.mobile.data.authFailureMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthFailureTest {
    @Test
    fun mapsCommonBackendAuthErrorsToActionableMessages() {
        assertEquals(
            "Email or password is incorrect. Check your details and try again.",
            authFailureMessage(401, "invalid_credentials"),
        )
        assertEquals(
            "This is a legacy account that must be secured on the original installation before it can be used here.",
            authFailureMessage(409, "password_setup_required"),
        )
        assertEquals(
            "This account requires email and password. Please sign in with your registered credentials.",
            authFailureMessage(401, "credentials_required"),
        )
        assertEquals(
            "An account with this email may already exist. Try signing in instead.",
            authFailureMessage(409, "account_already_exists"),
        )
        assertEquals(
            "CrediSafe authentication is temporarily unavailable. Please try again in a moment.",
            authFailureMessage(503, "session_unavailable"),
        )
    }

    @Test
    fun mapsUnknownErrorsByHttpStatusWithoutLeakingRawServerText() {
        assertEquals(
            "CrediSafe's authentication service is temporarily unavailable. Please try again shortly.",
            authFailureMessage(500, "database stack trace and connection string"),
        )
        assertEquals(
            "Too many attempts. Wait a few minutes before trying again.",
            authFailureMessage(429, null),
        )
    }
}
