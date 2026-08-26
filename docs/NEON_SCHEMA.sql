-- CrediSafe Neon PostgreSQL Schema

-- 1. Users Table
CREATE TABLE users (
    id UUID PRIMARY KEY,
    name TEXT,
    email TEXT UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    total_xp INTEGER DEFAULT 0,
    total_points INTEGER DEFAULT 0
);

-- 2. Vehicles Table
CREATE TABLE vehicles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    make TEXT,
    model TEXT,
    year INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Trips Table
CREATE TABLE trips (
    id UUID PRIMARY KEY, -- client_generated tripId
    user_id UUID NOT NULL REFERENCES users(id),
    vehicle_id UUID REFERENCES vehicles(id),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    distance_m REAL NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    safety_score INTEGER,
    telemetry_quality REAL,
    status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING, COMPLETED, VALIDATED
    engine_version TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 4. Trip Metrics (Aggregated)
CREATE TABLE trip_metrics (
    trip_id UUID PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
    avg_speed_kmh REAL DEFAULT 0,
    max_speed_kmh REAL DEFAULT 0,
    gps_quality REAL DEFAULT 0,
    sensor_quality REAL DEFAULT 0,
    idle_duration_ms BIGINT DEFAULT 0
);

-- 5. Driving Events
CREATE TABLE driving_events (
    id SERIAL PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    timestamp_ms BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    severity TEXT NOT NULL,
    confidence REAL NOT NULL,
    speed_kmh REAL,
    long_acc REAL,
    lat_acc REAL,
    detail TEXT,
    UNIQUE(trip_id, timestamp_ms)
);

-- 6. Telemetry Assets (Object Storage Metadata)
CREATE TABLE telemetry_assets (
    trip_id UUID PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
    asset_uri TEXT NOT NULL, -- S3/GCS URI
    sample_count INTEGER NOT NULL,
    file_size_bytes BIGINT,
    checksum_sha256 TEXT,
    compressed BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 7. XP Ledger (Auditable)
CREATE TABLE xp_ledger (
    id SERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    trip_id UUID REFERENCES trips(id),
    category TEXT NOT NULL, -- completion, safety, distance, clean_trip, streak
    points INTEGER NOT NULL,
    reason TEXT,
    engine_version TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 8. Reward Ledger (Auditable)
CREATE TABLE reward_ledger (
    id SERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    trip_id UUID REFERENCES trips(id),
    points INTEGER NOT NULL,
    activity_type TEXT NOT NULL, -- trip_reward, redemption
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 9. Sync Audit
CREATE TABLE sync_audit (
    id SERIAL PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    sync_status TEXT NOT NULL,
    attempt_count INTEGER DEFAULT 1,
    last_error TEXT,
    synced_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX idx_trips_user ON trips(user_id);
CREATE INDEX idx_events_trip ON driving_events(trip_id);
CREATE INDEX idx_xp_user ON xp_ledger(user_id);
CREATE INDEX idx_rewards_user ON reward_ledger(user_id);
