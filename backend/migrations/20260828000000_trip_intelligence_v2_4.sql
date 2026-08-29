-- CrediSafe v2.4 Trip Intelligence migration
-- Additive migration for existing Neon/PostgreSQL deployments.

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

CREATE INDEX IF NOT EXISTS idx_trips_classification ON trips(trip_classification);
CREATE INDEX IF NOT EXISTS idx_trips_status ON trips(status);
CREATE INDEX IF NOT EXISTS idx_trip_metrics_gps ON trip_metrics(gps_quality);

-- Idempotency helpers for a clean pilot database. These indexes are created
-- only after duplicate protection has been added at the service/transaction layer.
CREATE INDEX IF NOT EXISTS idx_xp_trip_category ON xp_ledger(trip_id, category);
CREATE INDEX IF NOT EXISTS idx_reward_trip_activity ON reward_ledger(trip_id, activity_type);
