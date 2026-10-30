-- CrediSafe Operations Center v2
-- Additive migration: preserves all existing accounts, journeys, ledgers and sessions.

ALTER TABLE public.users
  ADD COLUMN IF NOT EXISTS account_status TEXT NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS suspension_reason TEXT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'users_account_status_check'
      AND conrelid = 'public.users'::regclass
  ) THEN
    ALTER TABLE public.users
      ADD CONSTRAINT users_account_status_check
      CHECK (account_status IN ('ACTIVE', 'SUSPENDED'));
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.admin_members (
  user_id UUID PRIMARY KEY,
  role TEXT NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'SUPPORT', 'ANALYST', 'AUDITOR')),
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
  created_by UUID,
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_members_status_role
  ON public.admin_members (status, role);

CREATE TABLE IF NOT EXISTS public.admin_audit_logs (
  id UUID PRIMARY KEY,
  actor_user_id UUID NOT NULL,
  action TEXT NOT NULL,
  resource_type TEXT NOT NULL,
  resource_id TEXT,
  reason TEXT NOT NULL,
  request_id UUID UNIQUE,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_created
  ON public.admin_audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_actor_created
  ON public.admin_audit_logs (actor_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_resource
  ON public.admin_audit_logs (resource_type, resource_id);

CREATE TABLE IF NOT EXISTS public.admin_config (
  config_key TEXT PRIMARY KEY,
  config_value JSONB NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  updated_by UUID NOT NULL,
  reason TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Audit records are append-only for application roles. Even accidental UPDATE
-- or DELETE statements must fail; corrections are represented by new records.
CREATE OR REPLACE FUNCTION public.prevent_admin_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'admin_audit_logs is append-only';
END;
$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_trigger
    WHERE tgname = 'admin_audit_logs_immutable'
      AND tgrelid = 'public.admin_audit_logs'::regclass
      AND NOT tgisinternal
  ) THEN
    CREATE TRIGGER admin_audit_logs_immutable
      BEFORE UPDATE OR DELETE ON public.admin_audit_logs
      FOR EACH ROW EXECUTE FUNCTION public.prevent_admin_audit_mutation();
  END IF;
END $$;
