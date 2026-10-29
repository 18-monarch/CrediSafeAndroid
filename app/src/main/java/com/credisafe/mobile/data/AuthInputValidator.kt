package com.credisafe.mobile.data

private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

internal fun validateLoginInput(email: String, password: String): String? {
    if (!EMAIL_PATTERN.matches(email.trim())) return "Enter a valid email address."
    if (password.isEmpty()) return "Enter your password."
    if (password.length < 8 || password.length > 72) {
        return "Your password must be between 8 and 72 characters. For older accounts, use the original password."
    }
    return null
}

internal fun validateRegistrationInput(
    name: String,
    email: String,
    password: String,
    confirmPassword: String,
): String? {
    if (name.trim().length !in 2..80) return "Enter your full name (2–80 characters)."
    if (!EMAIL_PATTERN.matches(email.trim())) return "Enter a valid email address."
    if (password.length < 8 || password.length > 72) {
        return "Choose a password between 8 and 72 characters."
    }
    if (password != confirmPassword) return "Passwords do not match."
    return null
}
