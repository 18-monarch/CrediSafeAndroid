package com.credisafe.mobile.data.api

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class AuthRequest(
    val deviceId: String,
    val email: String? = null,
    val password: String? = null,
    val name: String? = null,
    val clientSecret: String? = null,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val expiresIn: Long,
    val userId: String,
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
    val zoneProfileJson: String? = null,
    val mobilityMode: String = "UNKNOWN",
    val mobilityConfidence: Int = 0,
    val mobilityReason: String? = null,
    val roadMatchRatio: Double = 0.0,
    val events: List<EventUpload>,
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
    val detail: String?,
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
    val data: String,
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
    val roadContextConfidence: Double = 0.0,
    val recognizedActivity: String = "UNKNOWN",
    val activityConfidence: Int = 0,
    val transportMode: String = "UNKNOWN",
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
    val providerAvailable: Boolean = false,
    val roadMatched: Boolean = false,
)

@Serializable
data class RoadContextRequest(
    val latitude: Double,
    val longitude: Double,
    val previousLatitude: Double? = null,
    val previousLongitude: Double? = null,
    val accuracyM: Double? = null,
    val speedKmh: Double? = null,
    val bearing: Double? = null,
)

@Serializable
data class SyncAckRequest(val tripIds: List<String>)

@Serializable
data class XpBreakdownDto(
    val code: String,
    val points: Int,
    val reason: String,
)

@Serializable
data class UploadResponse(
    val success: Boolean,
    val status: String? = null,
    val message: String? = null,
    val authoritativeXp: Int? = null,
    val authoritativePoints: Int? = null,
    val authoritativeSafetyScore: Int? = null,
    val engineVersion: String? = null,
    val eligible: Boolean? = null,
    val eligibilityReason: String? = null,
    val reasonCodes: List<String> = emptyList(),
    val breakdown: List<XpBreakdownDto> = emptyList(),
    val totalXp: Int? = null,
    val currentLevel: Int? = null,
    val currentLevelStartingXp: Int? = null,
    val nextLevelRequiredXp: Int? = null,
    val progressPercent: Double? = null,
    val xpRemaining: Int? = null,
    val dailyCapRemaining: Int? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    val version: String,
)

interface CloudApi {
    @GET("../health")
    suspend fun checkHealth(): Response<HealthResponse>

    @POST("auth/session")
    suspend fun createSession(@Body request: AuthRequest): Response<AuthResponse>

    @POST("trips")
    suspend fun createTrip(@Body request: TripUploadRequest): Response<UploadResponse>

    @POST("trips/{tripId}/events")
    suspend fun uploadEvents(
        @Path("tripId") tripId: String,
        @Body events: List<EventUpload>,
    ): Response<UploadResponse>

    @POST("trips/{tripId}/telemetry")
    suspend fun uploadTelemetry(
        @Path("tripId") tripId: String,
        @Body telemetry: TelemetryUploadRequest,
    ): Response<UploadResponse>

    @POST("trips/{tripId}/complete")
    suspend fun completeTrip(@Path("tripId") tripId: String): Response<UploadResponse>

    @GET("trips/{tripId}")
    suspend fun getTripDetails(@Path("tripId") tripId: String): Response<TripUploadRequest>

    @GET("trips")
    suspend fun listTrips(): Response<List<TripUploadRequest>>

    @POST("road/context")
    suspend fun getRoadContext(@Body request: RoadContextRequest): Response<RoadContextResponse>

    @POST("sync/ack")
    suspend fun acknowledgeSync(@Body request: SyncAckRequest): Response<UploadResponse>
}
