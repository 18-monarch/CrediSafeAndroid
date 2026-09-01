-- CrediSafe Initial Schema Migration

-- 1. Users Table
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    name TEXT,
    email TEXT UNIQUE,
    password_hash TEXT,
    account_type TEXT NOT NULL DEFAULT 'guest',
    email_verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    total_xp INTEGER DEFAULT 0,
    total_points INTEGER DEFAULT 0
);

-- 2. Vehicles Table
CREATE TABLE IF NOT EXISTS vehicles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    make TEXT,
    model TEXT,
    year INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Trips Table
CREATE TABLE IF NOT EXISTS trips (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    vehicle_id UUID REFERENCES vehicles(id),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    distance_m REAL NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    safety_score INTEGER,
    telemetry_quality REAL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    engine_version TEXT,
    xp INTEGER,
    reward_points INTEGER,
    trip_classification TEXT NOT NULL DEFAULT 'ELIGIBLE',
    eligibility_reason TEXT,
    anti_gaming_flags_json TEXT NOT NULL DEFAULT '[]',
    road_zone_type TEXT NOT NULL DEFAULT 'UNKNOWN',
    road_name TEXT,
    road_place_id TEXT,
    road_speed_limit_kmh REAL,
    road_context_confidence REAL NOT NULL DEFAULT 0,
    road_context_source TEXT NOT NULL DEFAULT 'NONE',
    zone_profile_json TEXT NOT NULL DEFAULT '{}',
    mobility_mode TEXT NOT NULL DEFAULT 'UNKNOWN',
    mobility_confidence INTEGER NOT NULL DEFAULT 0,
    mobility_reason TEXT,
    road_match_ratio REAL NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 4. Trip Metrics
CREATE TABLE IF NOT EXISTS trip_metrics (
    trip_id UUID PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
    avg_speed_kmh REAL NOT NULL DEFAULT 0,
    max_speed_kmh REAL NOT NULL DEFAULT 0,
    gps_quality REAL NOT NULL DEFAULT 0,
    sensor_quality REAL NOT NULL DEFAULT 0
);

-- 5. Driving Events
CREATE TABLE IF NOT EXISTS driving_events (
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
    UNIQUE(trip_id, timestamp_ms, event_type)
);

-- 5. Telemetry Assets
CREATE TABLE IF NOT EXISTS telemetry_assets (
    trip_id UUID PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
    asset_uri TEXT NOT NULL,
    sample_count INTEGER NOT NULL,
    file_size_bytes BIGINT,
    checksum_sha256 TEXT,
    compressed BOOLEAN DEFAULT TRUE,
    compression TEXT NOT NULL DEFAULT 'GZIP',
    payload_bytes BYTEA,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 6. XP Ledger
CREATE TABLE IF NOT EXISTS xp_ledger (
    id SERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    trip_id UUID REFERENCES trips(id),
    category TEXT NOT NULL,
    points INTEGER NOT NULL,
    reason TEXT,
    engine_version TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(trip_id, category)
);

-- 7. Reward Ledger
CREATE TABLE IF NOT EXISTS reward_ledger (
    id SERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    trip_id UUID REFERENCES trips(id),
    points INTEGER NOT NULL,
    activity_type TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(trip_id, activity_type)
);

-- 8. Sync Audit
CREATE TABLE IF NOT EXISTS sync_audit (
    id SERIAL PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    sync_status TEXT NOT NULL,
    attempt_count INTEGER DEFAULT 1,
    last_error TEXT,
    synced_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_trips_user ON trips(user_id);
CREATE INDEX IF NOT EXISTS idx_events_trip ON driving_events(trip_id);
CREATE INDEX IF NOT EXISTS idx_xp_user ON xp_ledger(user_id);
CREATE INDEX IF NOT EXISTS idx_rewards_user ON reward_ledger(user_id);
