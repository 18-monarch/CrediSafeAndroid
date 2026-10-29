package com.credisafe.mobile.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.credisafe.mobile.data.api.AuthRequest
import com.credisafe.mobile.data.api.AuthApiErrorEnvelope
import com.credisafe.mobile.data.api.CloudApi
import com.credisafe.mobile.data.api.RefreshSessionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.io.IOException
import java.net.SocketTimeoutException

class AuthRepository(context: Context, private val api: CloudApi) {
    companion object {
        // The UI, trip service and sync worker each create an AuthManager.
        // Coordinate refresh token rotation across all of them.
        private val refreshMutex = Mutex()
        private const val TOKEN_SAFETY_WINDOW_MS = 30_000L
    }

    private val responseJson = Json { ignoreUnknownKeys = true }

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

    suspend fun login(email: String? = null, password: String? = null, name: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val response = createSessionWithRetry(AuthRequest(getDeviceId(), email, password, name))
                val body = if (response.isSuccessful) response.body() else null
                if (body != null) {
                    refreshMutex.withLock {
                        saveSession(
                            body.accessToken,
                            body.expiresIn,
                            body.userId,
                            body.refreshToken,
                            body.refreshExpiresIn
                        )
                    }
                    Result.success(body.accessToken)
                } else {
                    Result.failure(serverFailure(response))
                }
            } catch (cancelled: CancellationException) {
                // Preserve coroutine cancellation instead of turning it into an auth failure.
                throw cancelled
            } catch (_: SocketTimeoutException) {
                Result.failure(IllegalStateException("Connection timed out. The server may be waking up; please try again."))
            } catch (_: IOException) {
                Result.failure(IllegalStateException("Could not connect to CrediSafe. Check your internet connection and try again."))
            } catch (_: Exception) {
                Result.failure(IllegalStateException("Could not complete authentication. Please try again."))
            }
        }

    /**
     * Render's free instance can be waking from idle when the user first signs in.
     * Retry one transient network/5xx failure only. Never retry credential or
     * account-conflict responses, and never leave the loading state spinning forever.
     */
    private suspend fun createSessionWithRetry(request: AuthRequest): retrofit2.Response<com.credisafe.mobile.data.api.AuthResponse> {
        var retried = false
        while (true) {
            try {
                val response = api.createSession(request)
                if (!retried && response.code() in 500..504) {
                    response.errorBody()?.close()
                    retried = true
                    delay(1_500)
                    continue
                }
                return response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (retried) throw error
                retried = true
                delay(1_500)
            }
        }
    }

    private fun serverFailure(response: retrofit2.Response<*>): Exception {
        val apiError = try {
            response.errorBody()?.string()?.let {
                responseJson.decodeFromString<AuthApiErrorEnvelope>(it).error
            }
        } catch (_: Exception) {
            null
        }
        return IllegalStateException(authFailureMessage(response.code(), apiError?.code))
    }

    private fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).commit()
        }
        return id
    }

    private fun saveSession(
        token: String,
        expires: Long,
        userId: String,
        refreshToken: String?,
        refreshExpiresIn: Long?
    ) {
        val edit = prefs.edit()
            .putString("access_token", token)
            .putLong("token_expires_at", System.currentTimeMillis() + expires * 1000)
            .putString("user_id", userId)
        if (refreshToken != null && refreshExpiresIn != null) {
            edit.putString("refresh_token", refreshToken)
                .putLong("refresh_expires_at", System.currentTimeMillis() + refreshExpiresIn * 1000)
        } else {
            edit.remove("refresh_token").remove("refresh_expires_at")
        }
        // Ensure the service can read new tokens immediately on other threads.
        edit.commit()
    }

    fun getAccessToken(): String? {
        val token = prefs.getString("access_token", null)
        val expiry = prefs.getLong("token_expires_at", 0L)
        return if (!token.isNullOrBlank() &&
            System.currentTimeMillis() + TOKEN_SAFETY_WINDOW_MS < expiry) token else null
    }

    fun hasSessionMaterial(): Boolean =
        getAccessToken() != null || (
            !prefs.getString("refresh_token", null).isNullOrBlank() &&
                System.currentTimeMillis() < prefs.getLong("refresh_expires_at", 0L)
            )

    /**
     * Network access uses a valid bearer. Refresh failures do not erase an
     * offline driver's locally recorded trips.
     */
    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                if (!forceRefresh) getAccessToken()?.let { return@withLock it }
                val refresh = prefs.getString("refresh_token", null)
                if (refresh.isNullOrBlank()) return@withLock null
                if (System.currentTimeMillis() >= prefs.getLong("refresh_expires_at", 0L)) {
                    clearTokens()
                    return@withLock null
                }
                try {
                    val response = api.refreshSession(RefreshSessionRequest(getDeviceId(), refresh))
                    val body = if (response.isSuccessful) response.body() else null
                    if (body != null) {
                        saveSession(
                            body.accessToken, body.expiresIn, body.userId,
                            body.refreshToken, body.refreshExpiresIn
                        )
                        body.accessToken
                    } else {
                        if (response.code() == 401 || response.code() == 403) clearTokens()
                        null
                    }
                } catch (_: Exception) {
                    // Temporary offline/backend failures must not sign the user out.
                    null
                }
            }
        }

    private fun clearTokens() {
        prefs.edit()
            .remove("access_token").remove("token_expires_at")
            .remove("refresh_token").remove("refresh_expires_at")
            .commit()
    }

    fun getUserId(): String? = prefs.getString("user_id", null)

    fun setSelectedVehicleId(id: String?) {
        prefs.edit().putString("selected_vehicle_id", id).apply()
    }

    fun getSelectedVehicleId(): String? = prefs.getString("selected_vehicle_id", null)

    fun logout() {
        val refresh = prefs.getString("refresh_token", null)
        val deviceId = getDeviceId()
        clearTokens()
        prefs.edit().remove("user_id").commit()
        if (!refresh.isNullOrBlank()) {
            // Best-effort server revocation; local logout works while offline.
            CoroutineScope(Dispatchers.IO).launch {
                try { api.revokeSession(RefreshSessionRequest(deviceId, refresh)) }
                catch (_: Exception) { /* Offline: bearer eventually expires. */ }
            }
        }
    }
}
