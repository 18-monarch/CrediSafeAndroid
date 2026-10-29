package com.credisafe.mobile.data

/**
 * Converts the structured error contract returned by the Android API into
 * stable user-facing guidance. Raw backend responses are never shown to users.
 */
internal fun authFailureMessage(httpStatus: Int, errorCode: String?): String =
    when (errorCode?.lowercase()) {
        "invalid_credentials" -> "Email or password is incorrect. Check your details and try again."
        "invalid_email" -> "Enter a valid email address."
        "invalid_password" -> "Choose a password between 8 and 72 characters."
        "invalid_device" -> "This installation could not be identified. Restart CrediSafe and try again."
        "password_setup_required" -> "This is a legacy account that must be secured on the original installation before it can be used here."
        "device_account_conflict" -> "This installation is already linked to another account. Sign in to that account or use a different installation to create a new account."
        "account_already_exists", "user_already_exists" -> "An account with this email may already exist. Try signing in instead."
        "session_expired", "unauthorized" -> "Your session has expired. Sign in again."
        "session_unavailable", "server_error" -> "CrediSafe authentication is temporarily unavailable. Please try again in a moment."
        "invalid_request" -> "Some required account details are missing. Check the form and try again."
        else -> when (httpStatus) {
            400 -> "Some account details are invalid. Check the form and try again."
            401 -> "Email or password is incorrect. Check your details and try again."
            409 -> "This account or installation is already registered. Try signing in or use the original installation for legacy-account setup."
            429 -> "Too many attempts. Wait a few minutes before trying again."
            in 500..599 -> "CrediSafe's authentication service is temporarily unavailable. Please try again shortly."
            else -> "Could not complete authentication (HTTP $httpStatus). Check your connection and try again."
        }
    }
