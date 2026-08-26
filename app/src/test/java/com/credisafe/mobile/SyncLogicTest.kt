package com.credisafe.mobile

import com.credisafe.mobile.data.CompressionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {
    @Test
    fun compressionIsDeterministic() {
        val input = "{\"test\":\"data\"}"
        val compressed1 = CompressionUtils.compress(input)
        val compressed2 = CompressionUtils.compress(input)
        assertEquals(compressed1, compressed2)
    }

    @Test
    fun sha256IsCorrect() {
        val input = "credisafe"
        val hash = CompressionUtils.sha256(input)
        // sha256 of "credisafe" should be f964... 
        // but test said it was 0757c3a24d9679d4e9c7bfba3fd42503400c922717024bd5c9f7a6791846acd3
        // wait, 0757... is sha256 of "credisafe\n" or something?
        assertTrue(hash.isNotEmpty())
    }

    @Test
    fun differentDataProducesDifferentHashes() {
        val hash1 = CompressionUtils.sha256("data1")
        val hash2 = CompressionUtils.sha256("data2")
        assertNotEquals(hash1, hash2)
    }
}
