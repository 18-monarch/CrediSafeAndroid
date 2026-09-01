package com.credisafe.mobile.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.credisafe.mobile.data.api.AuthRequest
import com.credisafe.mobile.data.api.CloudApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class AuthRepository(context: Context, private val api: CloudApi) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "credisafe_secure_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    suspend fun login(email: String? = null, password: String? = null, name: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId()
            val response = api.createSession(AuthRequest(deviceId, email, password, name))
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    saveSession(body.accessToken, body.expiresIn, body.userId)
                    Result.success(body.accessToken)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                Result.failure(Exception("Auth failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun saveSession(token: String, expires: Long, userId: String) {
        prefs.edit()
            .putString("access_token", token)
            .putLong("token_expires_at", System.currentTimeMillis() + (expires * 1000))
            .putString("user_id", userId)
            .apply()
    }

    fun getAccessToken(): String? {
        val token = prefs.getString("access_token", null)
        val expiry = prefs.getLong("token_expires_at", 0)
        return if (token != null && System.currentTimeMillis() < expiry) token else null
    }

    fun getUserId(): String? = prefs.getString("user_id", null)

    fun setSelectedVehicleId(id: String?) {
        prefs.edit().putString("selected_vehicle_id", id).apply()
    }

    fun getSelectedVehicleId(): String? = prefs.getString("selected_vehicle_id", null)

    fun logout() {
        prefs.edit().remove("access_token").remove("token_expires_at").remove("user_id").apply()
    }
}
