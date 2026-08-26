package com.credisafe.mobile.data

import java.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.security.MessageDigest

object CompressionUtils {
    fun compress(data: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data.toByteArray()) }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    fun sha256(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
