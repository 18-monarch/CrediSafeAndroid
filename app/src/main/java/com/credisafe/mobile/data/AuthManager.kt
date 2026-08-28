package com.credisafe.mobile.data

import android.content.Context
import com.credisafe.mobile.data.api.CloudApi
import com.credisafe.mobile.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit

class AuthManager(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()
    
    private val api: CloudApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.CREDISAFE_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CloudApi::class.java)
    }

    private val repository = AuthRepository(context, api)
    
    suspend fun login(email: String? = null, password: String? = null, name: String? = null) = repository.login(email, password, name)
    fun getUserId(): String? = repository.getUserId()
    fun getAuthToken(): String? = repository.getAccessToken()
    fun setSelectedVehicleId(id: String?) = repository.setSelectedVehicleId(id)
    fun getSelectedVehicleId(): String? = repository.getSelectedVehicleId()
    fun logout() = repository.logout()
}
