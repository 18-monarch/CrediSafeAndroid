-- CrediSafe v2.7 XP Progression & Immutable Ledger Migration
-- Forward-only, additive, safe to run repeatedly on historical databases.

ALTER TABLE xp_ledger ADD COLUMN IF NOT EXISTS delta_xp INTEGER;
ALTER TABLE xp_ledger ADD COLUMN IF NOT EXISTS reason_code TEXT;
ALTER TABLE xp_ledger ADD COLUMN IF NOT EXISTS source_type TEXT DEFAULT 'TRIP';
ALTER TABLE xp_ledger ADD COLUMN IF NOT EXISTS source_id UUID;
ALTER TABLE xp_ledger ADD COLUMN IF NOT EXISTS ruleset_version TEXT DEFAULT 'XP_RULESET_V1';
ALTER TABLE xp_ledger ADD COLUMN IF NOT EXISTS decision_metadata JSONB DEFAULT '{}';

-- Safely backfill historical rows using a composite ruleset_version per category
-- to prevent unique constraint failures on multi-row historical component entries.
UPDATE xp_ledger
SET delta_xp = points,
    reason_code = UPPER(category),
    source_type = 'TRIP',
    source_id = trip_id,
    ruleset_version = COALESCE(engine_version, 'LEGACY') || '_' || COALESCE(category, 'ITEM')
WHERE delta_xp IS NULL AND trip_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_xp_ledger_idempotency_v2
    ON xp_ledger (user_id, source_type, source_id, ruleset_version)
    WHERE source_id IS NOT NULL;

ALTER TABLE users ADD COLUMN IF NOT EXISTS current_level INTEGER DEFAULT 1;
ALTER TABLE users ADD COLUMN IF NOT EXISTS level_revision INTEGER DEFAULT 1;

-- Reconcile existing users using the same Level 1–1000 range and exact
-- cumulative threshold formula used by the TypeScript and Kotlin engines.
UPDATE users
SET current_level = COALESCE((
    SELECT level_series.lvl
    FROM generate_series(1, 1000) AS level_series(lvl)
    WHERE ROUND(100.0 * POWER(level_series.lvl - 1, 1.5))
        <= GREATEST(0, COALESCE(users.total_xp, 0))
    ORDER BY level_series.lvl DESC
    LIMIT 1
), 1)
WHERE current_level IS DISTINCT FROM COALESCE((
    SELECT level_series.lvl
    FROM generate_series(1, 1000) AS level_series(lvl)
    WHERE ROUND(100.0 * POWER(level_series.lvl - 1, 1.5))
        <= GREATEST(0, COALESCE(users.total_xp, 0))
    ORDER BY level_series.lvl DESC
    LIMIT 1
), 1);

CREATE INDEX IF NOT EXISTS idx_xp_ledger_user_source ON xp_ledger(user_id, source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_xp_ledger_user_created ON xp_ledger(user_id, created_at);
