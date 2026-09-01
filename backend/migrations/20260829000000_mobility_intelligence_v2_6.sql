-- CrediSafe v2.6 unified Trip + Road + Mobility Intelligence
-- Additive/idempotent migration for existing Neon/PostgreSQL deployments.

CREATE TABLE IF NOT EXISTS trip_metrics (
    trip_id UUID PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
    avg_speed_kmh REAL NOT NULL DEFAULT 0,
    max_speed_kmh REAL NOT NULL DEFAULT 0,
    gps_quality REAL NOT NULL DEFAULT 0,
    sensor_quality REAL NOT NULL DEFAULT 0
);

ALTER TABLE trips ADD COLUMN IF NOT EXISTS trip_classification TEXT NOT NULL DEFAULT 'ELIGIBLE';
ALTER TABLE trips ADD COLUMN IF NOT EXISTS eligibility_reason TEXT;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS anti_gaming_flags_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE trips ADD COLUMN IF NOT EXISTS road_zone_type TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE trips ADD COLUMN IF NOT EXISTS road_name TEXT;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS road_place_id TEXT;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS road_speed_limit_kmh REAL;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS road_context_confidence REAL NOT NULL DEFAULT 0;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS road_context_source TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE trips ADD COLUMN IF NOT EXISTS zone_profile_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE trips ADD COLUMN IF NOT EXISTS mobility_mode TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE trips ADD COLUMN IF NOT EXISTS mobility_confidence INTEGER NOT NULL DEFAULT 0;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS mobility_reason TEXT;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS road_match_ratio REAL NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_trips_classification ON trips(trip_classification);
CREATE INDEX IF NOT EXISTS idx_trips_status ON trips(status);
CREATE INDEX IF NOT EXISTS idx_trips_mobility_mode ON trips(mobility_mode);
CREATE INDEX IF NOT EXISTS idx_trip_metrics_gps ON trip_metrics(gps_quality);

CREATE INDEX IF NOT EXISTS idx_xp_trip_category ON xp_ledger(trip_id, category);
CREATE INDEX IF NOT EXISTS idx_reward_trip_activity ON reward_ledger(trip_id, activity_type);


-- Preserve distinct event types that happen at the same millisecond.
-- Older schemas used UNIQUE(trip_id, timestamp_ms), which could merge two
-- legitimate simultaneous safety events.
ALTER TABLE driving_events
    DROP CONSTRAINT IF EXISTS driving_events_trip_id_timestamp_ms_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_events_trip_time_type_unique
    ON driving_events(trip_id, timestamp_ms, event_type);
