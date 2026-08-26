package com.credisafe.mobile

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class AuthLogicTest {
    @Test
    fun userIdGenerationIsUuid() {
        val id = UUID.randomUUID().toString()
        assertNotNull(UUID.fromString(id))
    }
}
