-- CrediSafe v2.7 Open Mobility security and idempotency migration.
-- Apply after all earlier migrations. Safe to run repeatedly.

ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'guest';
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

UPDATE users
SET account_type = CASE
    WHEN password_hash IS NOT NULL THEN 'password'
    ELSE 'guest'
END
WHERE account_type IS NULL OR account_type NOT IN ('guest', 'password');

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_lower_unique ON users (LOWER(email));
CREATE UNIQUE INDEX IF NOT EXISTS idx_xp_trip_category_unique
    ON xp_ledger (trip_id, category) WHERE trip_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_reward_trip_activity_unique
    ON reward_ledger (trip_id, activity_type) WHERE trip_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_trips_road_place ON trips (road_place_id);
CREATE INDEX IF NOT EXISTS idx_trips_road_source ON trips (road_context_source);

ALTER TABLE telemetry_assets ADD COLUMN IF NOT EXISTS compression TEXT NOT NULL DEFAULT 'GZIP';
ALTER TABLE telemetry_assets ADD COLUMN IF NOT EXISTS payload_bytes BYTEA;
