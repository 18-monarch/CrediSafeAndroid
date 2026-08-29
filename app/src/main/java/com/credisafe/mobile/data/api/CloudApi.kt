package com.credisafe.mobile.data.api

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class AuthRequest(
    val deviceId: String,
    val email: String? = null,
    val password: String? = null,
    val name: String? = null,
    val clientSecret: String? = null
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val expiresIn: Long,
    val userId: String
)

@Serializable
data class TripUploadRequest(
    val tripId: String,
    val userId: String? = null,
    val vehicleId: String? = null,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceM: Double,
    val durationMs: Long,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val gpsQuality: Double = 0.0,
    val sensorQuality: Double = 0.0,
    val safetyScore: Int?,
    val xp: Int?,
    val rewardPoints: Int?,
    val telemetryQuality: Double,
    val antiGamingFlags: List<String>,
    val engineVersion: String? = null,
    val tripClassification: String = "ELIGIBLE",
    val eligibilityReason: String? = null,
    val roadZoneType: String = "UNKNOWN",
    val roadName: String? = null,
    val roadPlaceId: String? = null,
    val roadSpeedLimitKmh: Double? = null,
    val roadContextConfidence: Double = 0.0,
    val roadContextSource: String = "NONE",
    val events: List<EventUpload>
)

@Serializable
data class EventUpload(
    val timestampMs: Long,
    val type: String,
    val severity: String,
    val confidence: Double,
    val speedKmh: Double = 0.0,
    val longitudinalAccel: Double = 0.0,
    val lateralAccel: Double = 0.0,
    val detail: String?
)

@Serializable
data class TelemetryUploadRequest(
    val tripId: String,
    val sampleCount: Int,
    val samplingRateHz: Double,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val compression: String,
    val contentType: String,
    val sha256: String,
    val data: String // Base64 encoded compressed data
)

@Serializable
data class LiveTelemetryFrame(
    val tripId: String,
    val sequenceNumber: Long,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedKmh: Double,
    val bearing: Double?,
    val gpsAccuracy: Double?,
    val gpsQuality: Double,
    val longAcc: Double,
    val latAcc: Double,
    val verticalAcc: Double,
    val jerkLong: Double,
    val jerkLat: Double,
    val safetyEstimate: Int,
    val eventCount: Int,
    val telemetryQuality: Double,
    val sensorHz: Double,
    val jitterMs: Double,
    val roadZoneType: String = "UNKNOWN",
    val roadName: String? = null,
    val speedLimitKmh: Double? = null,
    val roadContextConfidence: Double = 0.0
)

@Serializable
data class SyncAckRequest(
    val tripIds: List<String>
)

@Serializable
data class UploadResponse(
    val success: Boolean,
    val message: String? = null,
    val authoritativeXp: Int? = null,
    val authoritativePoints: Int? = null,
    val engineVersion: String? = null
)



@Serializable
data class RoadContextResponse(
    val zoneType: String = "UNKNOWN",
    val roadName: String? = null,
    val placeId: String? = null,
    val jurisdiction: String? = null,
    val speedLimitKmh: Double? = null,
    val speedLimitTrusted: Boolean = false,
    val confidence: Double = 0.0,
    val source: String = "NONE",
    val snappedLatitude: Double? = null,
    val snappedLongitude: Double? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    val version: String
)

interface CloudApi {
    @GET("../health") // Health is usually top-level
    suspend fun checkHealth(): Response<HealthResponse>

    @POST("auth/session")
    suspend fun createSession(@Body request: AuthRequest): Response<AuthResponse>

    @POST("trips")
    suspend fun createTrip(@Body request: TripUploadRequest): Response<UploadResponse>

    @POST("trips/{tripId}/events")
    suspend fun uploadEvents(
        @Path("tripId") tripId: String,
        @Body events: List<EventUpload>
    ): Response<UploadResponse>

    @POST("trips/{tripId}/telemetry")
    suspend fun uploadTelemetry(
        @Path("tripId") tripId: String,
        @Body telemetry: TelemetryUploadRequest
    ): Response<UploadResponse>

    @POST("trips/{tripId}/complete")
    suspend fun completeTrip(
        @Path("tripId") tripId: String
    ): Response<UploadResponse>

    @GET("trips/{tripId}")
    suspend fun getTripDetails(@Path("tripId") tripId: String): Response<TripUploadRequest>

    @GET("trips")
    suspend fun listTrips(): Response<List<TripUploadRequest>>

    @GET("road/context")
    suspend fun getRoadContext(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("speedKmh") speedKmh: Double? = null,
        @Query("bearing") bearing: Double? = null,
    ): Response<RoadContextResponse>

    @POST("sync/ack")
    suspend fun acknowledgeSync(@Body request: SyncAckRequest): Response<UploadResponse>
}
