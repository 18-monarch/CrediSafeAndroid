package com.credisafe.mobile

import com.credisafe.mobile.data.validateLoginInput
import com.credisafe.mobile.data.validateRegistrationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthInputValidatorTest {
    @Test
    fun loginRejectsMalformedEmailAndUnsupportedPasswordLength() {
        assertEquals("Enter a valid email address.", validateLoginInput("not-an-email", "password123"))
        assertEquals(
            "Your password must be between 8 and 72 characters. For older accounts, use the original password.",
            validateLoginInput("driver@example.com", "short"),
        )
        assertNull(validateLoginInput("driver@example.com", "correct-horse"))
    }

    @Test
    fun registrationValidatesTrimmedNameEmailPasswordAndConfirmation() {
        assertEquals(
            "Enter your full name (2–80 characters).",
            validateRegistrationInput("  ", "driver@example.com", "correct-horse", "correct-horse"),
        )
        assertEquals(
            "Enter a valid email address.",
            validateRegistrationInput("Driver Name", "bad-email", "correct-horse", "correct-horse"),
        )
        assertEquals(
            "Choose a password between 8 and 72 characters.",
            validateRegistrationInput("Driver Name", "driver@example.com", "short", "short"),
        )
        assertEquals(
            "Passwords do not match.",
            validateRegistrationInput("Driver Name", "driver@example.com", "correct-horse", "different-pass"),
        )
        assertNull(
            validateRegistrationInput("  Driver Name ", "driver@example.com", "correct-horse", "correct-horse"),
        )
    }
}
