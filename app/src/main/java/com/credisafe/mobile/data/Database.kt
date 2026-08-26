package com.credisafe.mobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.credisafe.mobile.domain.XpItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class CrediSafeDb(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    // Ready for SQLCipher: swap to net.sqlcipher.database.SQLiteDatabase when needed
    // private val database: SQLiteDatabase get() = getWritableDatabase(PASS_PHRASE)

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCoreTables(db)
        addIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            safeAdd(db, "trips", "xp_breakdown_json", "TEXT")
            safeAdd(db, "trips", "eligibility_reason", "TEXT")
        }
        if (oldVersion < 3) {
            safeAdd(db, "trips", "telemetry_quality", "REAL NOT NULL DEFAULT 0")
            safeAdd(db, "trips", "anti_gaming_flags_json", "TEXT NOT NULL DEFAULT '[]'")
            safeAdd(db, "sensor_samples", "vertical_acc", "REAL")
            safeAdd(db, "sensor_samples", "jerk_long", "REAL")
            safeAdd(db, "sensor_samples", "jerk_lat", "REAL")
            safeAdd(db, "driving_events", "detail", "TEXT")
            addIndexes(db)
        }
        if (oldVersion < 4) {
            safeAdd(db, "trips", "sync_status", "TEXT NOT NULL DEFAULT 'PENDING'")
            safeAdd(db, "trips", "last_sync_at", "INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trips_sync ON trips(sync_status)")
        }
        if (oldVersion < 5) {
            safeAdd(db, "trips", "engine_version", "TEXT")
        }
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE users(id TEXT PRIMARY KEY, name TEXT, email TEXT, total_xp INTEGER DEFAULT 0, total_points INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE vehicles(id TEXT PRIMARY KEY, user_id TEXT NOT NULL, make TEXT, model TEXT, FOREIGN KEY(user_id) REFERENCES users(id))")
            db.execSQL("CREATE TABLE xp_ledger(id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id TEXT, category TEXT, points INTEGER, reason TEXT, engine_version TEXT, created_at INTEGER)")
            safeAdd(db, "trips", "user_id", "TEXT")
            safeAdd(db, "trips", "vehicle_id", "TEXT")
        }
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trips(
                id TEXT PRIMARY KEY,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                distance_m REAL NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                avg_speed_kmh REAL NOT NULL DEFAULT 0,
                max_speed_kmh REAL NOT NULL DEFAULT 0,
                gps_quality REAL NOT NULL DEFAULT 0,
                sensor_quality REAL NOT NULL DEFAULT 0,
                safety_score INTEGER,
                xp INTEGER,
                reward_points INTEGER,
                status TEXT NOT NULL,
                client_trip_id TEXT UNIQUE NOT NULL,
                xp_breakdown_json TEXT,
                eligibility_reason TEXT,
                telemetry_quality REAL NOT NULL DEFAULT 0,
                anti_gaming_flags_json TEXT NOT NULL DEFAULT '[]',
                engine_version TEXT,
                sync_status TEXT NOT NULL DEFAULT 'PENDING',
                last_sync_at INTEGER
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE driving_events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                type TEXT NOT NULL,
                severity TEXT NOT NULL,
                confidence REAL NOT NULL,
                speed_kmh REAL NOT NULL DEFAULT 0,
                long_acc REAL NOT NULL DEFAULT 0,
                lat_acc REAL NOT NULL DEFAULT 0,
                detail TEXT,
                FOREIGN KEY(trip_id) REFERENCES trips(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE sensor_samples(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                accuracy_m REAL,
                speed_kmh REAL,
                bearing_deg REAL,
                ax REAL,
                ay REAL,
                az REAL,
                gx REAL,
                gy REAL,
                gz REAL,
                lax REAL,
                lay REAL,
                laz REAL,
                rx REAL,
                ry REAL,
                rz REAL,
                rw REAL,
                long_acc REAL,
                lat_acc REAL,
                vertical_acc REAL,
                jerk_long REAL,
                jerk_lat REAL,
                FOREIGN KEY(trip_id) REFERENCES trips(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun addIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trips_started ON trips(started_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sensor_trip_time ON sensor_samples(trip_id, timestamp_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_trip_time ON driving_events(trip_id, timestamp_ms)")
    }

    private fun safeAdd(db: SQLiteDatabase, table: String, column: String, type: String) {
        try {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        } catch (_: Exception) {
            // Already present; migration remains idempotent.
        }
    }

    fun createTrip(): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.execSQL(
            "INSERT INTO trips(id,started_at,status,client_trip_id) VALUES(?,?,?,?)",
            arrayOf<Any?>(id, System.currentTimeMillis(), "RECORDING", id),
        )
        return id
    }

    fun insertEvent(e: DrivingEvent) {
        writableDatabase.execSQL(
            """
            INSERT INTO driving_events(
                trip_id,timestamp_ms,type,severity,confidence,
                speed_kmh,long_acc,lat_acc,detail
            ) VALUES(?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf<Any?>(
                e.tripId, e.timestampMs, e.type.name, e.severity.name,
                e.confidence, e.speedKmh, e.longitudinalAccel, e.lateralAccel, e.detail,
            ),
        )
    }

    fun insertEvents(events: List<DrivingEvent>) {
        if (events.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                """
                INSERT INTO driving_events(
                    trip_id,timestamp_ms,type,severity,confidence,
                    speed_kmh,long_acc,lat_acc,detail
                ) VALUES(?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            )
            events.forEach { e ->
                stmt.clearBindings()
                stmt.bindString(1, e.tripId)
                stmt.bindLong(2, e.timestampMs)
                stmt.bindString(3, e.type.name)
                stmt.bindString(4, e.severity.name)
                stmt.bindDouble(5, e.confidence)
                stmt.bindDouble(6, e.speedKmh)
                stmt.bindDouble(7, e.longitudinalAccel)
                stmt.bindDouble(8, e.lateralAccel)
                if (e.detail == null) stmt.bindNull(9) else stmt.bindString(9, e.detail)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertSample(s: SensorSample) = insertSamples(listOf(s))

    fun insertSamples(samples: List<SensorSample>) {
        if (samples.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                """
                INSERT INTO sensor_samples(
                    trip_id,timestamp_ms,latitude,longitude,accuracy_m,
                    speed_kmh,bearing_deg,ax,ay,az,gx,gy,gz,lax,lay,laz,
                    rx,ry,rz,rw,long_acc,lat_acc,vertical_acc,jerk_long,jerk_lat
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            )
            samples.forEach { s ->
                stmt.clearBindings()
                stmt.bindString(1, s.tripId)
                stmt.bindLong(2, s.timestampMs)
                bindDouble(stmt, 3, s.latitude)
                bindDouble(stmt, 4, s.longitude)
                bindDouble(stmt, 5, s.accuracyM)
                bindDouble(stmt, 6, s.speedKmh)
                bindDouble(stmt, 7, s.bearingDeg)
                bindDouble(stmt, 8, s.ax)
                bindDouble(stmt, 9, s.ay)
                bindDouble(stmt, 10, s.az)
                bindDouble(stmt, 11, s.gx)
                bindDouble(stmt, 12, s.gy)
                bindDouble(stmt, 13, s.gz)
                bindDouble(stmt, 14, s.lax)
                bindDouble(stmt, 15, s.lay)
                bindDouble(stmt, 16, s.laz)
                bindDouble(stmt, 17, s.rx)
                bindDouble(stmt, 18, s.ry)
                bindDouble(stmt, 19, s.rz)
                bindDouble(stmt, 20, s.rw)
                bindDouble(stmt, 21, s.longAcc)
                bindDouble(stmt, 22, s.latAcc)
                bindDouble(stmt, 23, s.verticalAcc)
                bindDouble(stmt, 24, s.jerkLong)
                bindDouble(stmt, 25, s.jerkLat)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun finish(id: String, s: TripSummary, xpItems: List<XpItem> = emptyList()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """
                UPDATE trips SET
                    ended_at=?,distance_m=?,duration_ms=?,avg_speed_kmh=?,max_speed_kmh=?,
                    gps_quality=?,sensor_quality=?,safety_score=?,xp=?,reward_points=?,
                    status='COMPLETED',xp_breakdown_json=?,eligibility_reason=?,
                    telemetry_quality=?,anti_gaming_flags_json=?,engine_version=?
                WHERE id=?
                """.trimIndent(),
                arrayOf<Any?>(
                    s.endedAt, s.distanceM, s.durationMs, s.avgSpeedKmh, s.maxSpeedKmh,
                    s.gpsQuality, s.sensorQuality, s.safetyScore, s.xp, s.rewardPoints,
                    s.xpBreakdownJson, s.eligibilityReason, s.telemetryQuality,
                    s.antiGamingFlagsJson, s.engineVersion, id,
                ),
            )
            
            xpItems.forEach { item ->
                db.execSQL(
                    "INSERT INTO xp_ledger(trip_id, category, points, reason, engine_version, created_at) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(id, item.code, item.points, item.detail, s.engineVersion, System.currentTimeMillis())
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateSyncStatus(tripId: String, status: String) {
        writableDatabase.execSQL(
            "UPDATE trips SET sync_status=?, last_sync_at=? WHERE id=?",
            arrayOf<Any?>(status, System.currentTimeMillis(), tripId)
        )
    }

    fun updateAuthoritativeResults(tripId: String, xp: Int, points: Int) {
        writableDatabase.execSQL(
            "UPDATE trips SET xp=?, reward_points=? WHERE id=?",
            arrayOf<Any?>(xp, points, tripId)
        )
    }

    fun getPendingTrips(): List<TripRecord> {
        val out = mutableListOf<TripRecord>()
        readableDatabase.query(
            "trips", null, "(sync_status='PENDING' OR sync_status='FAILED') AND status='COMPLETED'",
            null, null, null, "started_at ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += mapTripRecord(c)
            }
        }
        return out
    }

    private fun mapTripRecord(c: android.database.Cursor): TripRecord {
        fun i(n: String) = c.getColumnIndexOrThrow(n)
        return TripRecord(
            c.getString(i("id")),
            c.getLong(i("started_at")),
            if (c.isNull(i("ended_at"))) null else c.getLong(i("ended_at")),
            c.getDouble(i("distance_m")),
            c.getLong(i("duration_ms")),
            c.getDouble(i("avg_speed_kmh")),
            c.getDouble(i("max_speed_kmh")),
            c.getDouble(i("gps_quality")),
            c.getDouble(i("sensor_quality")),
            if (c.isNull(i("safety_score"))) null else c.getInt(i("safety_score")),
            if (c.isNull(i("xp"))) null else c.getInt(i("xp")),
            if (c.isNull(i("reward_points"))) null else c.getInt(i("reward_points")),
            c.getString(i("status")),
            getStringOrNull(c, "xp_breakdown_json"),
            getStringOrNull(c, "eligibility_reason"),
            if (hasColumn(c, "telemetry_quality") && !c.isNull(i("telemetry_quality"))) c.getDouble(i("telemetry_quality")) else null,
            getStringOrNull(c, "anti_gaming_flags_json"),
            getStringOrNull(c, "engine_version"),
            getStringOrNull(c, "sync_status") ?: "PENDING"
        )
    }

    fun listTrips(): List<TripRecord> {
        val out = mutableListOf<TripRecord>()
        readableDatabase.query("trips", null, null, null, null, null, "started_at DESC", "100").use { c ->
            while (c.moveToNext()) {
                out += mapTripRecord(c)
            }
        }
        return out
    }

    fun listEvents(tripId: String): List<DrivingEvent> {
        val out = mutableListOf<DrivingEvent>()
        readableDatabase.query(
            "driving_events",
            null,
            "trip_id=?",
            arrayOf(tripId),
            null,
            null,
            "timestamp_ms ASC"
        ).use { c ->
            while (c.moveToNext()) {
                fun i(n: String) = c.getColumnIndexOrThrow(n)
                out += DrivingEvent(
                    c.getString(i("trip_id")),
                    c.getLong(i("timestamp_ms")),
                    EventType.valueOf(c.getString(i("type"))),
                    EventSeverity.valueOf(c.getString(i("severity"))),
                    c.getDouble(i("confidence")),
                    c.getDouble(i("speed_kmh")),
                    c.getDouble(i("long_acc")),
                    c.getDouble(i("lat_acc")),
                    c.getString(i("detail"))
                )
            }
        }
        return out
    }

    fun listSamples(tripId: String): List<SensorSample> {
        val out = mutableListOf<SensorSample>()
        readableDatabase.query(
            "sensor_samples",
            null,
            "trip_id=?",
            arrayOf(tripId),
            null,
            null,
            "timestamp_ms ASC"
        ).use { c ->
            while (c.moveToNext()) {
                fun i(n: String) = c.getColumnIndexOrThrow(n)
                out += SensorSample(
                    c.getString(i("trip_id")),
                    c.getLong(i("timestamp_ms")),
                    getDoubleOrNull(c, "latitude"),
                    getDoubleOrNull(c, "longitude"),
                    getDoubleOrNull(c, "accuracy_m"),
                    getDoubleOrNull(c, "speed_kmh"),
                    getDoubleOrNull(c, "bearing_deg"),
                    getDoubleOrNull(c, "ax"),
                    getDoubleOrNull(c, "ay"),
                    getDoubleOrNull(c, "az"),
                    getDoubleOrNull(c, "gx"),
                    getDoubleOrNull(c, "gy"),
                    getDoubleOrNull(c, "gz"),
                    getDoubleOrNull(c, "lax"),
                    getDoubleOrNull(c, "lay"),
                    getDoubleOrNull(c, "laz"),
                    getDoubleOrNull(c, "rx"),
                    getDoubleOrNull(c, "ry"),
                    getDoubleOrNull(c, "rz"),
                    getDoubleOrNull(c, "rw"),
                    getDoubleOrNull(c, "long_acc"),
                    getDoubleOrNull(c, "lat_acc"),
                    getDoubleOrNull(c, "vertical_acc"),
                    getDoubleOrNull(c, "jerk_long"),
                    getDoubleOrNull(c, "jerk_lat")
                )
            }
        }
        return out
    }

    private fun getDoubleOrNull(c: android.database.Cursor, column: String): Double? {
        val idx = c.getColumnIndex(column)
        return if (idx < 0 || c.isNull(idx)) null else c.getDouble(idx)
    }

    fun tripCountOnLocalDate(epochMs: Long): Int {
        val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val start = Instant.parse("${date}T00:00:00Z").toEpochMilli()
        val end = start + 86_400_000L
        readableDatabase.query(
            "trips",
            arrayOf("id"),
            "status='COMPLETED' AND ended_at>=? AND ended_at<?",
            arrayOf(start.toString(), end.toString()),
            null, null, null,
        ).use { c ->
            return c.count
        }
    }

    fun streakDaysBeforeOrIncluding(endedAtMs: Long): Int {
        val completed = mutableSetOf<String>()
        readableDatabase.query(
            "trips",
            arrayOf("ended_at"),
            "status='COMPLETED' AND ended_at IS NOT NULL",
            null, null, null, "ended_at DESC",
        ).use { c ->
            while (c.moveToNext()) {
                val t = c.getLong(0)
                if (t <= endedAtMs) {
                    completed += Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                }
            }
        }
        if (completed.isEmpty()) return 1
        var cursor = Instant.ofEpochMilli(endedAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        var streak = 0
        while (completed.contains(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak.coerceAtLeast(1)
    }

    fun hasCompletedTripOnDate(endedAtMs: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(endedAtMs).atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        readableDatabase.query(
            "trips",
            arrayOf("id"),
            "status='COMPLETED' AND ended_at>=? AND ended_at<?",
            arrayOf(start.toString(), end.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun previousStreakDays(endedAtMs: Long): Int {
        val zone = ZoneId.systemDefault()
        val currentDate = Instant.ofEpochMilli(endedAtMs).atZone(zone).toLocalDate()
        val dates = mutableSetOf<String>()

        readableDatabase.query(
            "trips",
            arrayOf("ended_at"),
            "status='COMPLETED' AND ended_at IS NOT NULL",
            null,
            null,
            null,
            "ended_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val tripDate = Instant.ofEpochMilli(cursor.getLong(0)).atZone(zone).toLocalDate()
                if (tripDate.isBefore(currentDate)) {
                    dates += tripDate.toString()
                }
            }
        }

        var cursorDate = currentDate.minusDays(1)
        var streak = 0

        while (dates.contains(cursorDate.toString())) {
            streak++
            cursorDate = cursorDate.minusDays(1)
        }

        return streak
    }

    fun exportJson(): String {
        val root = JSONObject()
            .put("schemaVersion", 3)
            .put("app", "CrediSafe Android Pilot")
            .put("exportedAt", System.currentTimeMillis())
        root.put("trips", table("trips"))
        root.put("events", table("driving_events"))
        root.put("sensorSamples", table("sensor_samples"))
        return root.toString(2)
    }

    private fun table(t: String): JSONArray {
        val a = JSONArray()
        readableDatabase.query(t, null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) {
                    o.put(c.getColumnName(i), if (c.isNull(i)) JSONObject.NULL else c.getString(i))
                }
                a.put(o)
            }
        }
        return a
    }

    private fun bindDouble(stmt: android.database.sqlite.SQLiteStatement, index: Int, value: Double?) {
        if (value == null || value.isNaN()) stmt.bindNull(index) else stmt.bindDouble(index, value)
    }

    private fun getStringOrNull(c: android.database.Cursor, column: String): String? {
        val idx = c.getColumnIndex(column)
        return if (idx < 0 || c.isNull(idx)) null else c.getString(idx)
    }

    private fun hasColumn(c: android.database.Cursor, name: String): Boolean = c.getColumnIndex(name) >= 0

    companion object {
        private const val DB_NAME = "credisafe.db"
        private const val DB_VERSION = 6
    }
}
