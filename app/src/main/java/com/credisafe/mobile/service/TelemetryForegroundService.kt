package com.credisafe.mobile.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.location.LocationCompat
import com.credisafe.mobile.R
import com.credisafe.mobile.data.CrediSafeDb
import com.credisafe.mobile.data.DrivingEvent
import com.credisafe.mobile.data.LiveStreamManager
import com.credisafe.mobile.data.MobilityContextStore
import com.credisafe.mobile.data.RoadContextRepository
import com.credisafe.mobile.data.api.LiveTelemetryFrame
import com.credisafe.mobile.data.EventSeverity
import com.credisafe.mobile.data.EventType
import com.credisafe.mobile.data.SensorSample
import com.credisafe.mobile.data.TripSummary
import com.credisafe.mobile.domain.AntiGamingAssessment
import com.credisafe.mobile.domain.LiveTelemetry
import com.credisafe.mobile.domain.SafetyEngine
import com.credisafe.mobile.domain.TelematicsConfig
import com.credisafe.mobile.domain.TelematicsQuality
import com.credisafe.mobile.domain.TelemetryMath
import com.credisafe.mobile.domain.LatLngPoint
import com.credisafe.mobile.domain.MobilityAccumulator
import com.credisafe.mobile.domain.MobilityDecision
import com.credisafe.mobile.domain.MobilityEngine
import com.credisafe.mobile.domain.OverspeedLevel
import com.credisafe.mobile.domain.RoadContext
import com.credisafe.mobile.domain.RoadRuleEngine
import com.credisafe.mobile.domain.TripValidityEngine
import com.credisafe.mobile.domain.ZoneProfileAccumulator
import com.credisafe.mobile.domain.VehicleAcceleration
import com.credisafe.mobile.domain.WorldAcceleration
import com.credisafe.mobile.domain.XpEngine
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build

class TelemetryForegroundService : Service(), SensorEventListener {
    private lateinit var vibrator: Vibrator

    private lateinit var db: CrediSafeDb
    private lateinit var sensorManager: SensorManager
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var liveStream: LiveStreamManager
    private lateinit var roadContextRepository: RoadContextRepository
    private lateinit var mobilityActivityManager: MobilityActivityManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tripId: String? = null
    private var startedAt = 0L
    private var lastLocation: Location? = null
    
    private var liveFrameSequence = 0L
    private var lastLiveFrameTs = 0L

    private var distanceM = 0.0
    private var maxSpeedKmh = 0.0
    private var speedSumKmh = 0.0
    private var speedSamples = 0
    private var gpsQualitySum = 0.0
    private var gpsSamples = 0
    private var sensorSamples = 0L
    private var locationSamples = 0L
    private var suspiciousJumps = 0
    private var mockLocationCount = 0
    private var movingLocationSamples = 0L

    @Volatile private var currentRoadContext = RoadContext()
    private var lastRoadContextLookupTs = 0L
    private var lastRoadContextLookupLocation: Location? = null
    @Volatile private var roadProviderSamples = 0
    @Volatile private var roadMatchedSamples = 0
    private val zoneAccumulator = ZoneProfileAccumulator()
    private val mobilityAccumulator = MobilityAccumulator()
    private var latestMobilityDecision = MobilityDecision.unknown()

    private var ax = Double.NaN
    private var ay = Double.NaN
    private var az = Double.NaN
    private var gx = Double.NaN
    private var gy = Double.NaN
    private var gz = Double.NaN
    private var lax = Double.NaN
    private var lay = Double.NaN
    private var laz = Double.NaN
    private var rx = Double.NaN
    private var ry = Double.NaN
    private var rz = Double.NaN
    private var rw = Double.NaN
    private var longAcc = 0.0
    private var latAcc = 0.0
    private var verticalAcc = 0.0
    private var previousLongAcc = 0.0
    private var previousLatAcc = 0.0
    private var previousSensorTs = 0L
    private var lastPersistTs = 0L
    private var lastWorld: WorldAcceleration? = null
    
    private var sensorHz = 0.0
    private var sensorJitterMs = 0.0
    private var processLatencyMs = 0.0
    private val sensorDts = mutableListOf<Long>()

    private val events = mutableListOf<DrivingEvent>()
    private val tripRoute = mutableListOf<LatLngPoint>()
    private val cooldownMs = mutableMapOf<EventType, Long>()
    private val pendingSamples = mutableListOf<SensorSample>()

    private val rotationMatrix = FloatArray(9)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = CrediSafeDb(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        liveStream = LiveStreamManager(this)
        roadContextRepository = RoadContextRepository(this)
        mobilityActivityManager = MobilityActivityManager(this)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "CrediSafe active journey",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val userId = intent.getStringExtra(EXTRA_USER_ID)
                val vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID)
                startTrip(userId, vehicleId)
            }
            ACTION_STOP -> stopTrip()
        }
        return START_NOT_STICKY
    }

    private fun startTrip(userId: String? = null, vehicleId: String? = null) {
        if (tripId != null) return

        if (!hasLocationPermission()) {
            TripSession.update { it.copy(lastError = "Location permission is required.") }
            stopSelf()
            return
        }

        tripId = db.createTrip(userId, vehicleId)
        startedAt = System.currentTimeMillis()
        lastLocation = null
        distanceM = 0.0
        maxSpeedKmh = 0.0
        speedSumKmh = 0.0
        speedSamples = 0
        gpsQualitySum = 0.0
        gpsSamples = 0
        sensorSamples = 0
        locationSamples = 0
        suspiciousJumps = 0
        mockLocationCount = 0
        movingLocationSamples = 0
        currentRoadContext = RoadContext()
        lastRoadContextLookupTs = 0L
        lastRoadContextLookupLocation = null
        roadProviderSamples = 0
        roadMatchedSamples = 0
        latestMobilityDecision = MobilityDecision.unknown()
        zoneAccumulator.reset()
        mobilityAccumulator.reset()
        MobilityContextStore.reset()
        db.purgeExpiredDiscardedTrips()
        ax = Double.NaN
        ay = Double.NaN
        az = Double.NaN
        gx = Double.NaN
        gy = Double.NaN
        gz = Double.NaN
        lax = Double.NaN
        lay = Double.NaN
        laz = Double.NaN
        rx = Double.NaN
        ry = Double.NaN
        rz = Double.NaN
        rw = Double.NaN
        longAcc = 0.0
        latAcc = 0.0
        verticalAcc = 0.0
        previousLongAcc = 0.0
        previousLatAcc = 0.0
        previousSensorTs = 0L
        lastPersistTs = 0L
        events.clear()
        tripRoute.clear()
        cooldownMs.clear()
        pendingSamples.clear()
        liveFrameSequence = 0L
        lastLiveFrameTs = 0L

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        TripSession.set(LiveTelemetry(active = true, tripId = tripId))
        liveStream.start(tripId!!)
        mobilityActivityManager.start()
        startLocationUpdates()
        startSensorUpdates()
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TelematicsConfig.LOCATION_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(TelematicsConfig.MIN_LOCATION_INTERVAL_MS)
            .setMinUpdateDistanceMeters(TelematicsConfig.MIN_LOCATION_DISTANCE_M)
            .setMaxUpdateDelayMillis(TelematicsConfig.MAX_LOCATION_DELAY_MS)
            .build()

        if (!hasLocationPermission()) return
        locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun startSensorUpdates() {
        register(Sensor.TYPE_ACCELEROMETER, TelematicsConfig.SENSOR_PERIOD_US)
        register(Sensor.TYPE_GYROSCOPE, TelematicsConfig.SENSOR_PERIOD_US)
        register(Sensor.TYPE_LINEAR_ACCELERATION, TelematicsConfig.SENSOR_PERIOD_US)
        register(Sensor.TYPE_ROTATION_VECTOR, TelematicsConfig.SENSOR_PERIOD_US)
    }

    private fun register(type: Int, samplingPeriodUs: Int) {
        val sensor = sensorManager.getDefaultSensor(type) ?: return
        sensorManager.registerListener(this, sensor, samplingPeriodUs, TelematicsConfig.SENSOR_MAX_LATENCY_US)
    }

    private fun onLocation(location: Location) {
        val id = tripId ?: return
        // If an existing v2.5 user grants Physical Activity after the trip
        // starts, begin recognition automatically on the next location update.
        mobilityActivityManager.start()
        if (LocationCompat.isMock(location)) {
            mockLocationCount++
            emit(id, EventType.TELEMETRY_ANOMALY, EventSeverity.HIGH, 1.0, 0.0, "Mock location detected.", location.time)
        }
        val speedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6).coerceIn(0.0, TelematicsConfig.MAX_REASONABLE_SPEED_KMH)
        } else {
            0.0
        }

        lastLocation?.let { previous ->
            val dt = (location.time - previous.time) / 1000.0
            val segmentM = previous.distanceTo(location).toDouble()
            val impliedKmh = if (dt > 0.0) segmentM / dt * 3.6 else 0.0

            if (dt in 0.2..10.0 && segmentM <= TelematicsConfig.MAX_REASONABLE_SEGMENT_M && impliedKmh <= TelematicsConfig.MAX_REASONABLE_SPEED_KMH) {
                distanceM += segmentM
            } else if (segmentM > TelematicsConfig.MAX_REASONABLE_SEGMENT_M || impliedKmh > TelematicsConfig.MAX_REASONABLE_SPEED_KMH) {
                suspiciousJumps++
                emit(id, EventType.GPS_ANOMALY, EventSeverity.MEDIUM, 0.8, impliedKmh, "Suspicious GPS jump detected.", location.time)
            }
        }

        lastLocation = location
        tripRoute.add(LatLngPoint(location.latitude, location.longitude))
        locationSamples++
        if (speedKmh >= TelematicsConfig.MIN_MOVING_SPEED_KMH) movingLocationSamples++
        mobilityAccumulator.add(MobilityContextStore.state.value, location.time)
        speedSamples++
        speedSumKmh += speedKmh
        maxSpeedKmh = max(maxSpeedKmh, speedKmh)
        gpsSamples++
        gpsQualitySum += gpsQuality(location.accuracy)

        refreshRoadContext(location, speedKmh)
        detectOverspeed(id, speedKmh, location.time)
        updateTelemetry()
        persistMergedSample(force = true)
    }

    private fun gpsQuality(accuracyM: Float): Double = when {
        accuracyM <= 5f -> 1.0
        accuracyM <= 10f -> 0.95
        accuracyM <= 20f -> 0.85
        accuracyM <= 40f -> 0.65
        accuracyM <= 70f -> 0.45
        else -> 0.2
    }

    private fun refreshRoadContext(location: Location, speedKmh: Double) {
        val now = System.currentTimeMillis()
        val elapsed = if (lastRoadContextLookupTs > 0L) now - lastRoadContextLookupTs else Long.MAX_VALUE
        val movedM = lastRoadContextLookupLocation?.distanceTo(location)?.toDouble() ?: Double.POSITIVE_INFINITY

        val trustedLimitNeedsRefresh =
            currentRoadContext.speedLimitTrusted && elapsed >= TRUSTED_LIMIT_REFRESH_MS
        val movedEnough =
            elapsed >= ROAD_CONTEXT_MIN_REFRESH_MS && movedM >= ROAD_CONTEXT_MIN_DISTANCE_M
        val maxAgeReached = elapsed >= ROAD_CONTEXT_MAX_REFRESH_MS

        if (!trustedLimitNeedsRefresh && !movedEnough && !maxAgeReached) return

        val previousLookup = lastRoadContextLookupLocation?.let(::Location)
        lastRoadContextLookupTs = now
        lastRoadContextLookupLocation = Location(location)

        serviceScope.launch {
            val context = roadContextRepository.lookup(
                latitude = location.latitude,
                longitude = location.longitude,
                previousLatitude = previousLookup?.latitude,
                previousLongitude = previousLookup?.longitude,
                accuracyM = location.accuracy.takeIf { location.hasAccuracy() }?.toDouble(),
                speedKmh = speedKmh,
                bearing = location.bearing.takeIf { location.hasBearing() }?.toDouble(),
            )
            if (context != null && tripId != null) {
                currentRoadContext = context
                if (context.providerAvailable) {
                    roadProviderSamples++
                    if (context.roadMatched) roadMatchedSamples++
                }
                zoneAccumulator.add(context)
            }
        }
    }

    private fun detectOverspeed(id: String, speedKmh: Double, timestampMs: Long) {
        val decision = RoadRuleEngine.overspeed(
            speedKmh = speedKmh,
            context = currentRoadContext,
            nowMs = timestampMs,
        )
        when (decision.level) {
            OverspeedLevel.MAJOR -> emit(
                id,
                EventType.OVERSPEED_MAJOR,
                EventSeverity.HIGH,
                0.97,
                speedKmh,
                decision.detail,
                timestampMs,
            )
            OverspeedLevel.MINOR -> emit(
                id,
                EventType.OVERSPEED_MINOR,
                EventSeverity.MEDIUM,
                0.92,
                speedKmh,
                decision.detail,
                timestampMs,
            )
            OverspeedLevel.NONE -> Unit
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (tripId == null) return
        val startTs = System.nanoTime()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values.getOrNull(0)?.toDouble() ?: ax
                ay = event.values.getOrNull(1)?.toDouble() ?: ay
                az = event.values.getOrNull(2)?.toDouble() ?: az
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values.getOrNull(0)?.toDouble() ?: gx
                gy = event.values.getOrNull(1)?.toDouble() ?: gy
                gz = event.values.getOrNull(2)?.toDouble() ?: gz
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                lax = event.values.getOrNull(0)?.toDouble() ?: lax
                lay = event.values.getOrNull(1)?.toDouble() ?: lay
                laz = event.values.getOrNull(2)?.toDouble() ?: laz
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val values = event.values
                rx = values.getOrNull(0)?.toDouble() ?: rx
                ry = values.getOrNull(1)?.toDouble() ?: ry
                rz = values.getOrNull(2)?.toDouble() ?: rz
                rw = if (values.size > 3) values[3].toDouble() else Double.NaN
            }
        }

        val now = event.timestamp / 1_000_000L
        if (previousSensorTs > 0L) {
            val dtMs = (now - previousSensorTs)
            sensorDts.add(dtMs)
            if (sensorDts.size > 50) {
                sensorDts.removeAt(0)
                val avg = sensorDts.average()
                sensorHz = 1000.0 / avg
                sensorJitterMs = sensorDts.map { abs(it - avg) }.average()
            }

            val dt = (dtMs.coerceIn(10L, 500L)) / 1000.0
            val world = worldFrameAcceleration()
            lastWorld = world
            val vehicle = vehicleFrameAcceleration(world)
            longAcc = vehicle.longitudinal
            latAcc = vehicle.lateral
            verticalAcc = vehicle.vertical

            val jerkLong = (longAcc - previousLongAcc) / dt
            val jerkLat = (latAcc - previousLatAcc) / dt

            val speed = lastLocation?.let { if (it.hasSpeed()) it.speed * 3.6 else 0.0 } ?: 0.0
            val id = tripId ?: return

            if (speed >= TelematicsConfig.MIN_MOVING_SPEED_KMH && longAcc <= TelematicsConfig.HARSH_BRAKING_MPS2) {
                emit(id, EventType.HARSH_BRAKING, EventSeverity.MEDIUM, confidenceFor(abs(longAcc), abs(TelematicsConfig.HARSH_BRAKING_MPS2), 5.0), speed,
                    "Longitudinal deceleration exceeded the harsh-braking threshold.", System.currentTimeMillis())
            }
            if (speed >= TelematicsConfig.MIN_MOVING_SPEED_KMH && longAcc >= TelematicsConfig.HARSH_ACCELERATION_MPS2) {
                emit(id, EventType.HARSH_ACCELERATION, EventSeverity.MEDIUM, confidenceFor(abs(longAcc), TelematicsConfig.HARSH_ACCELERATION_MPS2, 5.0), speed,
                    "Longitudinal acceleration exceeded the harsh-acceleration threshold.", System.currentTimeMillis())
            }
            if (speed >= TelematicsConfig.MIN_CORNER_SPEED_KMH && abs(latAcc) >= TelematicsConfig.AGGRESSIVE_LATERAL_MPS2) {
                emit(id, EventType.AGGRESSIVE_CORNERING, EventSeverity.HIGH, confidenceFor(abs(latAcc), TelematicsConfig.AGGRESSIVE_LATERAL_MPS2, 7.0), speed,
                    "Lateral acceleration exceeded the aggressive-cornering threshold.", System.currentTimeMillis())
            }

            if (now - lastPersistTs >= TelematicsConfig.STORAGE_INTERVAL_MS) {
                val location = lastLocation
                pendingSamples += SensorSample(
                    tripId = id,
                    timestampMs = System.currentTimeMillis(),
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    accuracyM = location?.accuracy?.toDouble(),
                    speedKmh = speed,
                    bearingDeg = location?.bearing?.toDouble(),
                    ax = finiteOrNull(ax),
                    ay = finiteOrNull(ay),
                    az = finiteOrNull(az),
                    gx = finiteOrNull(gx),
                    gy = finiteOrNull(gy),
                    gz = finiteOrNull(gz),
                    lax = finiteOrNull(lax),
                    lay = finiteOrNull(lay),
                    laz = finiteOrNull(laz),
                    rx = finiteOrNull(rx),
                    ry = finiteOrNull(ry),
                    rz = finiteOrNull(rz),
                    rw = finiteOrNull(rw),
                    longAcc = longAcc,
                    latAcc = latAcc,
                    verticalAcc = verticalAcc,
                    jerkLong = jerkLong,
                    jerkLat = jerkLat,
                )
                sensorSamples++
                lastPersistTs = now
                if (pendingSamples.size >= 10) flushSamples()
                updateTelemetry()
            }

            previousLongAcc = longAcc
            previousLatAcc = latAcc
        }
        previousSensorTs = now
        processLatencyMs = (System.nanoTime() - startTs) / 1_000_000.0
    }

    private fun worldFrameAcceleration(): WorldAcceleration {
        val rotationValues = if (rx.isFinite() && ry.isFinite() && rz.isFinite()) {
            floatArrayOf(rx.toFloat(), ry.toFloat(), rz.toFloat())
        } else {
            null
        }

        return TelemetryMath.worldAcceleration(
            rotationVector = rotationValues,
            linearAcceleration = doubleArrayOf(
                finiteOrZero(lax),
                finiteOrZero(lay),
                finiteOrZero(laz),
            ),
        )
    }

    private fun vehicleFrameAcceleration(
        world: WorldAcceleration,
    ): VehicleAcceleration {
        return TelemetryMath.vehicleAcceleration(
            world = world,
            bearingDeg = lastLocation?.bearing?.toDouble(),
        )
    }

    private fun confidenceFor(value: Double, threshold: Double, strong: Double): Double {
        return ((value - threshold) / max(0.001, strong - threshold))
            .coerceIn(0.0, 1.0) * 0.5 + 0.5
    }

    private fun emit(
        id: String,
        type: EventType,
        severity: EventSeverity,
        confidence: Double,
        speedKmh: Double,
        detail: String,
        timestampMs: Long,
    ) {
        val previous = cooldownMs[type] ?: 0L
        if (timestampMs - previous < TelematicsConfig.EVENT_COOLDOWN_MS) return
        cooldownMs[type] = timestampMs

        val event = DrivingEvent(
            tripId = id,
            timestampMs = timestampMs,
            type = type,
            severity = severity,
            confidence = confidence.coerceIn(0.0, 1.0),
            speedKmh = speedKmh,
            longitudinalAccel = longAcc,
            lateralAccel = latAcc,
            detail = detail,
        )
        events += event
        db.insertEvent(event)
        
        // Haptic feedback for safety events
        if (severity == EventSeverity.HIGH) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(50, 100))
        }
    }

    private fun persistMergedSample(force: Boolean = false) {
        if (!force) return
        if (pendingSamples.isNotEmpty()) flushSamples()
    }

    private fun flushSamples() {
        if (pendingSamples.isEmpty()) return
        db.insertSamples(pendingSamples.toList())
        pendingSamples.clear()
    }

    private fun updateTelemetry() {
        val trip = tripId ?: return
        val gpsQuality = if (gpsSamples > 0) gpsQualitySum / gpsSamples else 0.0
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        
        val quality = TelematicsQuality.calculate(
            gpsQuality = gpsQuality,
            sensorSamples = sensorSamples,
            elapsedMs = elapsed,
            suspiciousJumps = suspiciousJumps,
            mockLocations = mockLocationCount,
            maxSpeedKmh = maxSpeedKmh
        )

        val score = SafetyEngine.score(
            events.groupingBy { it.type }.eachCount(),
            gpsQuality,
            distanceM,
            quality.overall,
            suspiciousJumps.coerceAtMost(5),
        )

        val liveMobility = MobilityContextStore.state.value
            .takeIf { it.isFresh(System.currentTimeMillis()) }
            ?: com.credisafe.mobile.domain.MobilitySnapshot()
        val (dominantActivity, dominantConfidence) = mobilityAccumulator.dominant()
        latestMobilityDecision = MobilityEngine.decide(
            dominantActivity = dominantActivity,
            activityConfidence = dominantConfidence,
            roadProviderSamples = roadProviderSamples,
            roadMatchedSamples = roadMatchedSamples,
            avgSpeedKmh = if (speedSamples > 0) speedSumKmh / speedSamples else 0.0,
            maxSpeedKmh = maxSpeedKmh,
        )
        val zoneProfile = zoneAccumulator.snapshot()

        TripSession.set(
            LiveTelemetry(
                active = true,
                tripId = trip,
                streamStatus = TripSession.state.value.streamStatus,
                elapsedMs = elapsed,
                distanceM = distanceM,
                speedKmh = lastLocation?.let { if (it.hasSpeed()) it.speed * 3.6 else 0.0 } ?: 0.0,
                gpsAccuracyM = lastLocation?.accuracy?.toDouble(),
                gpsQuality = gpsQuality,
                longAcc = longAcc,
                latAcc = latAcc,
                verticalAcc = verticalAcc,
                sensorCount = sensorSamples,
                locationCount = locationSamples,
                eventCount = events.size,
                safetyScore = score,
                telemetryQuality = quality.overall,
                latestEvent = events.lastOrNull(),
                lastError = TripSession.state.value.lastError,
                rawAx = finiteOrZero(ax),
                rawAy = finiteOrZero(ay),
                rawAz = finiteOrZero(az),
                rawGx = finiteOrZero(gx),
                rawGy = finiteOrZero(gy),
                rawGz = finiteOrZero(gz),
                worldNorth = lastWorld?.north ?: 0.0,
                worldEast = lastWorld?.east ?: 0.0,
                worldUp = lastWorld?.up ?: 0.0,
                jerkLong = if (previousSensorTs > 0L) (longAcc - previousLongAcc) / (((eventTimestamp() - previousSensorTs).coerceIn(10L, 500L)) / 1000.0) else 0.0,
                jerkLat = if (previousSensorTs > 0L) (latAcc - previousLatAcc) / (((eventTimestamp() - previousSensorTs).coerceIn(10L, 500L)) / 1000.0) else 0.0,
                sensorHz = sensorHz,
                sensorJitterMs = sensorJitterMs,
                processLatencyMs = processLatencyMs,
                roadContext = currentRoadContext,
                mobility = liveMobility,
                transportMode = latestMobilityDecision.mode,
                zoneProfile = zoneProfile,
                route = tripRoute.toList()
            ),
        )

        // Real-time streaming: every 2 seconds
        val now = System.currentTimeMillis()
        if (now - lastLiveFrameTs >= 2000L) {
            val frame = LiveTelemetryFrame(
                tripId = trip,
                sequenceNumber = liveFrameSequence++,
                timestampMs = now,
                latitude = lastLocation?.latitude,
                longitude = lastLocation?.longitude,
                speedKmh = lastLocation?.let { if (it.hasSpeed()) it.speed * 3.6 else 0.0 } ?: 0.0,
                bearing = lastLocation?.bearing?.toDouble(),
                gpsAccuracy = lastLocation?.accuracy?.toDouble(),
                gpsQuality = gpsQuality,
                longAcc = longAcc,
                latAcc = latAcc,
                verticalAcc = verticalAcc,
                jerkLong = if (previousSensorTs > 0L) (longAcc - previousLongAcc) / (((eventTimestamp() - previousSensorTs).coerceIn(10L, 500L)) / 1000.0) else 0.0,
                jerkLat = if (previousSensorTs > 0L) (latAcc - previousLatAcc) / (((eventTimestamp() - previousSensorTs).coerceIn(10L, 500L)) / 1000.0) else 0.0,
                safetyEstimate = score,
                eventCount = events.size,
                telemetryQuality = quality.overall,
                sensorHz = sensorHz,
                jitterMs = sensorJitterMs,
                roadZoneType = currentRoadContext.zoneType.name,
                roadName = currentRoadContext.roadName,
                speedLimitKmh = currentRoadContext.speedLimitKmh.takeIf { currentRoadContext.speedLimitTrusted },
                roadContextConfidence = currentRoadContext.confidence,
                recognizedActivity = liveMobility.activity.name,
                activityConfidence = liveMobility.confidence,
                transportMode = latestMobilityDecision.mode.name
            )
            liveStream.sendFrame(frame)
            lastLiveFrameTs = now
        }
    }

    private fun eventTimestamp(): Long = System.currentTimeMillis() // Simplification for jerk in updateTelemetry

    private fun stopTrip() {
        val id = tripId ?: return

        locationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
        mobilityActivityManager.stop()
        flushSamples()

        val endedAt = System.currentTimeMillis()
        val elapsed = (endedAt - startedAt).coerceAtLeast(0L)
        val gpsQuality = if (gpsSamples > 0) gpsQualitySum / gpsSamples else 0.0
        val telemetryQuality = TelematicsQuality.score(
            gpsQuality,
            sensorSamples,
            elapsed,
            events.size,
            suspiciousJumps,
        )

        val anomalyFlags = AntiGamingAssessment(buildList {
            if (suspiciousJumps > 0) add("suspicious_gps_jump:$suspiciousJumps")
            if (mockLocationCount > 0) add("mock_location:$mockLocationCount")
            if (maxSpeedKmh > TelematicsConfig.MAX_REASONABLE_SPEED_KMH) add("impossible_speed")
            if (telemetryQuality < TripValidityEngine.MIN_TELEMETRY_QUALITY) add("low_telemetry_quality")
        })

        val (dominantActivity, dominantActivityConfidence) = mobilityAccumulator.dominant()
        val averageSpeed = if (speedSamples > 0) speedSumKmh / speedSamples else 0.0
        val mobilityDecision = MobilityEngine.decide(
            dominantActivity = dominantActivity,
            activityConfidence = dominantActivityConfidence,
            roadProviderSamples = roadProviderSamples,
            roadMatchedSamples = roadMatchedSamples,
            avgSpeedKmh = averageSpeed,
            maxSpeedKmh = maxSpeedKmh,
        )
        val roadMatchRatio = if (roadProviderSamples > 0) {
            roadMatchedSamples.toDouble() / roadProviderSamples.toDouble()
        } else 0.0
        val zoneProfile = zoneAccumulator.snapshot()

        val movingRatio = if (locationSamples > 0L) {
            movingLocationSamples.toDouble() / locationSamples.toDouble()
        } else 0.0

        val validity = TripValidityEngine.assess(
            distanceM = distanceM,
            durationMs = elapsed,
            gpsQuality = gpsQuality,
            telemetryQuality = telemetryQuality,
            movingLocationRatio = movingRatio,
            locationSamples = locationSamples,
            antiGamingFlags = anomalyFlags.flags,
            mobility = mobilityDecision,
            nowMs = endedAt,
        )

        val score = SafetyEngine.score(
            events.groupingBy { it.type }.eachCount(),
            gpsQuality,
            distanceM,
            telemetryQuality,
            suspiciousJumps.coerceAtMost(5),
        )

        val firstTripOfDay = validity.eligible && !db.hasCompletedTripOnDate(endedAt)
        val streakDays = if (firstTripOfDay) db.previousStreakDays(endedAt) + 1 else 1

        val validityFlags = if (validity.eligible) {
            anomalyFlags.flags
        } else {
            anomalyFlags.flags + "trip_validity:${validity.classification.name.lowercase()}"
        }

        val xp = XpEngine.calculate(
            mode = com.credisafe.mobile.domain.TripMode.REAL_GPS,
            safetyScore = score,
            distanceM = distanceM,
            durationMs = elapsed,
            gpsQuality = gpsQuality,
            events = events,
            streakDays = streakDays,
            firstTripOfDay = firstTripOfDay,
            antiGamingFlags = validityFlags,
            mobility = mobilityDecision,
            zoneProfile = zoneProfile,
        )

        val eligibilityReason = when {
            validity.eligible -> xp.eligibilityReason
            validity.reason.isNotBlank() -> validity.reason
            else -> xp.eligibilityReason
        }

        val zoneProfileJson = JSONObject()
            .put("dominantZone", zoneProfile.dominantZone.name)
            .put("confidence", zoneProfile.confidence)
            .put("urbanRatio", zoneProfile.urbanRatio)
            .put("highwayRatio", zoneProfile.highwayRatio)
            .put("residentialRatio", zoneProfile.residentialRatio)
            .toString()

        val breakdownJson = JSONObject()
            .put("version", XpEngine.VERSION)
            .put("tripValidityVersion", TripValidityEngine.VERSION)
            .put("classification", validity.classification.name)
            .put("eligible", validity.eligible && xp.eligible)
            .put("rewardEligible", validity.eligible && xp.rewardEligible)
            .put("subtotal", if (validity.eligible) xp.subtotal else 0)
            .put("total", if (validity.eligible) xp.total else 0)
            .put("rewardPoints", if (validity.eligible) xp.rewardPoints else 0)
            .put("note", if (validity.eligible) xp.note else validity.reason)
            .put("eligibilityReason", eligibilityReason)
            .put("antiGamingFlags", JSONArray(validityFlags))
            .put("mobilityMode", mobilityDecision.mode.name)
            .put("mobilityConfidence", mobilityDecision.confidence)
            .put("mobilityReason", mobilityDecision.reason)
            .put("roadMatchRatio", roadMatchRatio)
            .put("roadZoneType", currentRoadContext.zoneType.name)
            .put("roadName", currentRoadContext.roadName)
            .put("roadSpeedLimitKmh", currentRoadContext.speedLimitKmh)
            .put("roadContextConfidence", currentRoadContext.confidence)
            .put("zoneProfile", JSONObject(zoneProfileJson))
            .put(
                "items",
                JSONArray().apply {
                    if (validity.eligible) {
                        xp.items.forEach {
                            put(
                                JSONObject()
                                    .put("code", it.code)
                                    .put("label", it.label)
                                    .put("points", it.points)
                                    .put("detail", it.detail)
                            )
                        }
                    }
                },
            )
            .toString()

        val finalXp = if (validity.eligible) xp.total else 0
        val finalRewardPoints = if (validity.eligible) xp.rewardPoints else 0

        db.finish(
            id,
            TripSummary(
                endedAt = endedAt,
                distanceM = distanceM,
                durationMs = elapsed,
                avgSpeedKmh = averageSpeed,
                maxSpeedKmh = maxSpeedKmh,
                gpsQuality = gpsQuality,
                sensorQuality = min(1.0, telemetryQuality),
                safetyScore = score,
                xp = finalXp,
                rewardPoints = finalRewardPoints,
                xpBreakdownJson = breakdownJson,
                eligibilityReason = eligibilityReason,
                telemetryQuality = telemetryQuality,
                antiGamingFlagsJson = JSONArray(validityFlags).toString(),
                engineVersion = xp.engineVersion,
                tripClassification = validity.classification.name,
                discardAfterMs = validity.discardAfterMs,
                roadZoneType = currentRoadContext.zoneType.name,
                roadName = currentRoadContext.roadName,
                roadPlaceId = currentRoadContext.placeId,
                roadSpeedLimitKmh = currentRoadContext.speedLimitKmh.takeIf { currentRoadContext.speedLimitTrusted },
                roadContextConfidence = currentRoadContext.confidence,
                roadContextSource = currentRoadContext.source.name,
                zoneProfileJson = zoneProfileJson,
                mobilityMode = mobilityDecision.mode.name,
                mobilityConfidence = mobilityDecision.confidence,
                mobilityReason = mobilityDecision.reason,
                roadMatchRatio = roadMatchRatio,
            ),
            if (validity.eligible) xp.items else emptyList(),
        )

        TripSession.update {
            it.copy(
                active = false,
                safetyScore = score,
                telemetryQuality = telemetryQuality,
                roadContext = currentRoadContext,
                mobility = MobilityContextStore.state.value
                    .takeIf { it.isFresh(endedAt) }
                    ?: com.credisafe.mobile.domain.MobilitySnapshot(),
                transportMode = mobilityDecision.mode,
                zoneProfile = zoneProfile,
                lastError = if (!validity.eligible) eligibilityReason else null,
            )
        }

        tripId = null
        liveStream.stop()

        if (validity.shouldSync) {
            SyncWorker.enqueue(applicationContext)
        }

        db.purgeExpiredDiscardedTrips()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): android.app.Notification {
        val stopIntent = Intent(this, TelemetryForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.credisafe_icon)
            .setContentTitle("CrediSafe • Journey active")
            .setContentText("${"%.1f".format(distanceM / 1000.0)} km • ${events.size} events")
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.credisafe_icon, "Stop Journey", stopPendingIntent)
            .build()
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun finiteOrNull(value: Double): Double? = value.takeIf { it.isFinite() }
    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        locationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
        mobilityActivityManager.stop()
        flushSamples()
        liveStream.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "CREDSAFE_START"
        const val ACTION_STOP = "CREDSAFE_STOP"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_VEHICLE_ID = "vehicle_id"
        const val CHANNEL = "credisafe"
        const val NOTIFICATION_ID = 11
        const val TRUSTED_LIMIT_REFRESH_MS = 30_000L
        const val ROAD_CONTEXT_MIN_REFRESH_MS = 60_000L
        const val ROAD_CONTEXT_MAX_REFRESH_MS = 180_000L
        const val ROAD_CONTEXT_MIN_DISTANCE_M = 1_000.0
    }
}
