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

-- Reconcile existing users' levels based on total_xp using exact cumulative thresholds:
-- totalXpRequired(level) = round(100 * (level - 1)^1.5)
-- Level 1: 0, Level 2: 100, Level 3: 283, Level 4: 520, Level 5: 800, Level 6: 1118, Level 7: 1470, Level 8: 1852
UPDATE users
SET current_level = CASE
    WHEN COALESCE(total_xp, 0) >= 1852 THEN 8
    WHEN COALESCE(total_xp, 0) >= 1470 THEN 7
    WHEN COALESCE(total_xp, 0) >= 1118 THEN 6
    WHEN COALESCE(total_xp, 0) >= 800 THEN 5
    WHEN COALESCE(total_xp, 0) >= 520 THEN 4
    WHEN COALESCE(total_xp, 0) >= 283 THEN 3
    WHEN COALESCE(total_xp, 0) >= 100 THEN 2
    ELSE 1
END
WHERE current_level IS NULL OR current_level = 1 OR current_level != CASE
    WHEN COALESCE(total_xp, 0) >= 1852 THEN 8
    WHEN COALESCE(total_xp, 0) >= 1470 THEN 7
    WHEN COALESCE(total_xp, 0) >= 1118 THEN 6
    WHEN COALESCE(total_xp, 0) >= 800 THEN 5
    WHEN COALESCE(total_xp, 0) >= 520 THEN 4
    WHEN COALESCE(total_xp, 0) >= 283 THEN 3
    WHEN COALESCE(total_xp, 0) >= 100 THEN 2
    ELSE 1
END;

CREATE INDEX IF NOT EXISTS idx_xp_ledger_user_source ON xp_ledger(user_id, source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_xp_ledger_user_created ON xp_ledger(user_id, created_at);
