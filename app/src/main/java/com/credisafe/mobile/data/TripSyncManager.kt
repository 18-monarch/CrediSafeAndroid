package com.credisafe.mobile.data

import android.content.Context
import com.credisafe.mobile.data.api.CloudApi
import com.credisafe.mobile.data.api.EventUpload
import com.credisafe.mobile.data.api.SyncAckRequest
import com.credisafe.mobile.data.api.TelemetryUploadRequest
import com.credisafe.mobile.data.api.TripUploadRequest
import com.credisafe.mobile.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.json.JSONArray
import retrofit2.Retrofit

class TripSyncManager(context: Context) {
    private val db = CrediSafeDb(context)
    private val auth = AuthManager(context)
    
    private val json = Json { ignoreUnknownKeys = true }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Authorization", auth.getAuthToken() ?: "")
                .method(original.method, original.body)
                .build()
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

    suspend fun checkHealth(): Boolean {
        return try {
            val response = api.checkHealth()
            response.isSuccessful && response.body()?.status == "ok"
        } catch (e: Exception) {
            false
        }
    }

    suspend fun syncPendingTrips(): Result<Int> {
        val pending = db.getPendingTrips()
        if (pending.isEmpty()) return Result.success(0)
        
        var syncedCount = 0
        var lastError: Throwable? = null
        
        for (trip in pending) {
            try {
                db.updateSyncStatus(trip.id, "SYNCING")

                // 1. Create Trip
                val flags = try {
                    val arr = JSONArray(trip.antiGamingFlagsJson ?: "[]")
                    List(arr.length()) { arr.getString(it) }
                } catch (e: Exception) {
                    emptyList()
                }

                val createRequest = TripUploadRequest(
                    tripId = trip.id,
                    userId = auth.getUserId(),
                    startedAt = trip.startedAt,
                    endedAt = trip.endedAt,
                    distanceM = trip.distanceM,
                    durationMs = trip.durationMs,
                    avgSpeedKmh = trip.avgSpeedKmh,
                    maxSpeedKmh = trip.maxSpeedKmh,
                    gpsQuality = trip.gpsQuality,
                    sensorQuality = trip.sensorQuality,
                    safetyScore = trip.safetyScore,
                    xp = trip.xp,
                    rewardPoints = trip.rewardPoints,
                    telemetryQuality = trip.telemetryQuality ?: 0.0,
                    antiGamingFlags = flags,
                    engineVersion = trip.engineVersion,
                    events = emptyList() // Events uploaded separately
                )
                
                val createResponse = api.createTrip(createRequest)
                if (!createResponse.isSuccessful) throw Exception("Failed to create trip: ${createResponse.code()}")

                // 2. Upload Events
                val events = db.listEvents(trip.id).map {
                    EventUpload(
                        timestampMs = it.timestampMs,
                        type = it.type.name,
                        severity = it.severity.name,
                        confidence = it.confidence,
                        detail = it.detail
                    )
                }
                if (events.isNotEmpty()) {
                    val eventResponse = api.uploadEvents(trip.id, events)
                    if (!eventResponse.isSuccessful) throw Exception("Failed to upload events: ${eventResponse.code()}")
                }

                // 3. Upload Raw Telemetry (Compressed)
                val samples = db.listSamples(trip.id)
                if (samples.isNotEmpty()) {
                    val samplesJson = json.encodeToString(samples)
                    val compressedData = CompressionUtils.compress(samplesJson)
                    val sha256 = CompressionUtils.sha256(samplesJson)
                    
                    val telemetryRequest = TelemetryUploadRequest(
                        tripId = trip.id,
                        sampleCount = samples.size,
                        samplingRateHz = 50.0, // Known constant for this engine
                        firstTimestamp = samples.first().timestampMs,
                        lastTimestamp = samples.last().timestampMs,
                        compression = "GZIP",
                        contentType = "application/json",
                        sha256 = sha256,
                        data = compressedData
                    )
                    val telemetryResponse = api.uploadTelemetry(trip.id, telemetryRequest)
                    if (!telemetryResponse.isSuccessful) throw Exception("Failed to upload telemetry: ${telemetryResponse.code()}")
                }

                // 4. Complete Trip & Get Authoritative Results
                val completeResponse = api.completeTrip(trip.id)
                val body = completeResponse.body()
                if (completeResponse.isSuccessful && body?.success == true) {
                    db.updateSyncStatus(trip.id, "SYNCED")
                    if (body.authoritativeXp != null && body.authoritativePoints != null) {
                        db.updateAuthoritativeResults(trip.id, body.authoritativeXp, body.authoritativePoints)
                    }
                    
                    // 5. Acknowledge Sync
                    api.acknowledgeSync(SyncAckRequest(listOf(trip.id)))
                    
                    syncedCount++
                } else {
                    throw Exception("Failed to complete trip: ${completeResponse.code()}")
                }
            } catch (e: Exception) {
                db.updateSyncStatus(trip.id, "FAILED")
                lastError = e
            }
        }
        
        return if (lastError != null && syncedCount == 0) {
            Result.failure(lastError)
        } else {
            Result.success(syncedCount)
        }
    }
}
