package com.credisafe.mobile.data

import android.content.Context
import com.credisafe.mobile.BuildConfig
import com.credisafe.mobile.data.api.CloudApi
import com.credisafe.mobile.domain.RoadContext
import com.credisafe.mobile.domain.RoadContextSource
import com.credisafe.mobile.domain.RoadZoneType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Thin, authenticated client for road context. The Google server-side key stays
 * on the backend; Android only talks to the CrediSafe API.
 */
class RoadContextRepository(context: Context) {
    private val auth = AuthManager(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val token = auth.getAuthToken()
            val request = original.newBuilder().apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            chain.proceed(request)
        }
        .build()

    private val api: CloudApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.CREDISAFE_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CloudApi::class.java)
    }

    suspend fun lookup(
        latitude: Double,
        longitude: Double,
        speedKmh: Double?,
        bearing: Double?,
    ): RoadContext? {
        return try {
            val response = api.getRoadContext(latitude, longitude, speedKmh, bearing)
            val body = response.body()
            if (!response.isSuccessful || body == null) return null
            RoadContext(
                zoneType = enumOrDefault(body.zoneType, RoadZoneType.UNKNOWN),
                roadName = body.roadName,
                placeId = body.placeId,
                jurisdiction = body.jurisdiction,
                speedLimitKmh = body.speedLimitKmh,
                speedLimitTrusted = body.speedLimitTrusted,
                confidence = body.confidence.coerceIn(0.0, 1.0),
                source = enumOrDefault(body.source, RoadContextSource.NONE),
                snappedLatitude = body.snappedLatitude,
                snappedLongitude = body.snappedLongitude,
                updatedAtMs = System.currentTimeMillis(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback
}
