-- Apply before deploying an API version that issues refresh tokens.
-- Existing access JWTs and trip data remain unaffected.
CREATE TABLE IF NOT EXISTS auth_refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_auth_refresh_user ON auth_refresh_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_refresh_expiry ON auth_refresh_sessions(expires_at);

-- Run a recurring cleanup for expired/revoked records via scheduled maintenance;
-- do not truncate or reset existing sessions during deployment.
