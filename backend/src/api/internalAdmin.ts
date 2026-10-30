import { Router, type Request, type Response, type NextFunction } from 'express';
import { timingSafeEqual, randomUUID } from 'node:crypto';
import { validate as isUuid } from 'uuid';
import db from '../db/knex.js';

type AdminRole = 'OWNER' | 'ADMIN' | 'SUPPORT' | 'ANALYST' | 'AUDITOR';
type AdminPermission = 'overview:read' | 'users:read' | 'users:write' | 'trips:read' | 'xp:read' | 'audit:read' | 'team:read' | 'team:manage' | 'config:read' | 'config:write' | 'system:read';
type AdminActor = { userId: string; role: AdminRole };
const ROLES: AdminRole[] = ['OWNER','ADMIN','SUPPORT','ANALYST','AUDITOR'];
const ROLE_PERMISSIONS: Record<AdminRole, AdminPermission[]> = {
  OWNER: ['overview:read','users:read','users:write','trips:read','xp:read','audit:read','team:read','team:manage','config:read','config:write','system:read'],
  ADMIN: ['overview:read','users:read','users:write','trips:read','xp:read','audit:read','team:read','config:read','config:write','system:read'],
  SUPPORT: ['overview:read','users:read','users:write','trips:read','system:read'],
  ANALYST: ['overview:read','trips:read','xp:read','system:read'],
  AUDITOR: ['overview:read','trips:read','audit:read','team:read','config:read','system:read'],
};


/**
 * Read-only, server-to-server operational bridge. No browser CORS, sessions,
 * user PII, GPS coordinates, route histories or raw telemetry are returned.
 * Disabled unless a sufficiently strong shared secret is explicitly set.
 */
export const internalAdminRouter = Router();

export function permittedInternalSecret(presented: unknown, configured: string | undefined): boolean {
  if (typeof presented !== 'string' || typeof configured !== 'string' ||
      configured.length < 32 || presented.length !== configured.length) return false;
  const given = Buffer.from(presented, 'utf8');
  const expected = Buffer.from(configured, 'utf8');
  return given.length === expected.length && timingSafeEqual(given, expected);
}

function authenticate(req: Request, res: Response, next: NextFunction) {
  const configured = process.env.CREDISAFE_ADMIN_BRIDGE_SECRET;
  if (!configured || configured.length < 32) {
    return res.status(503).json({ error: 'Team monitoring is not configured' });
  }
  const supplied = req.header('x-credisafe-admin-bridge');
  if (!permittedInternalSecret(supplied, configured)) {
    return res.status(403).json({ error: 'Forbidden' });
  }
  res.set('Cache-Control', 'no-store, private');
  next();
}

internalAdminRouter.use(authenticate);

internalAdminRouter.get('/overview', async (_req, res) => {
  try {
    const [
      users, trips, events, assets, statuses, eventTypes,
      recentTrips, syncFailures, recentSyncFailures,
    ] = await Promise.all([
      db('users').count('* as count').first(),
      db('trips').count('* as count').first(),
      db('driving_events').count('* as count').first(),
      db('telemetry_assets').count('* as count').first(),
      db('trips').select('status').count('* as count').groupBy('status'),
      db('driving_events').select('event_type').count('* as count').groupBy('event_type'),
      db('trips')
        .select('id','started_at','duration_ms','distance_m','safety_score','telemetry_quality','status','engine_version')
        .orderBy('created_at', 'desc').limit(12),
      db('sync_audit').whereNotIn('sync_status', ['SUCCESS','SYNCED','CONFIRMED'])
        .count('* as count').first(),
      db('sync_audit').select('trip_id','sync_status','attempt_count','synced_at')
        .whereNotIn('sync_status', ['SUCCESS','SYNCED','CONFIRMED'])
        .orderBy('synced_at','desc').limit(10),
    ]);
    const total = (v: unknown) => Number(v ?? 0) || 0;
    res.json({
      source: 'android-neon',
      fetchedAt: new Date().toISOString(),
      totals: {
        users: total(users?.count),
        trips: total(trips?.count),
        events: total(events?.count),
        telemetryUploads: total(assets?.count),
        syncIssues: total(syncFailures?.count),
      },
      tripsByStatus: (statuses as Array<{status:string;count:unknown}>)
        .map(v=>({status:v.status,count:total(v.count)})),
      eventsByType: (eventTypes as Array<{event_type:string;count:unknown}>)
        .map(v=>({type:v.event_type,count:total(v.count)})),
      recentTrips: recentTrips.map(t=>({
        id:t.id,
        startedAt:t.started_at,
        durationMs:total(t.duration_ms),
        distanceM:total(t.distance_m),
        safetyScore:t.safety_score == null ? null : total(t.safety_score),
        telemetryQuality:t.telemetry_quality == null ? null : Number(t.telemetry_quality),
        status:t.status,
        engineVersion:t.engine_version,
      })),
      recentSyncIssues: recentSyncFailures.map(v=>({
        tripId:v.trip_id,
        status:v.sync_status,
        attempts:total(v.attempt_count),
        recordedAt:v.synced_at,
      })),
      privacy:'Aggregated and de-identified operational view; no user, vehicle or GPS coordinates returned.',
    });
  } catch {
    res.status(503).json({error:'Monitoring metrics temporarily unavailable'});
  }
});


// Admin actor identity is supplied only by the authenticated Vercel server route.
// The database membership is re-checked on every request; browser-supplied role
// headers are never accepted directly.
function parseAdminRole(value: unknown): value is AdminRole {
  return typeof value === 'string' && ROLES.includes(value as AdminRole);
}
async function authorizeAdmin(req: Request, res: Response, permission: AdminPermission): Promise<AdminActor | null> {
  const userId = req.header('x-credisafe-admin-user-id');
  const role = req.header('x-credisafe-admin-role');
  if (!userId || !isUuid(userId) || !parseAdminRole(role)) {
    res.status(401).json({ error: 'Admin identity required' });
    return null;
  }
  try {
    const member = await db('admin_members').where({ user_id: userId }).first();
    if (!member || member.status !== 'ACTIVE' || member.role !== role) {
      res.status(403).json({ error: 'Admin membership is inactive or has changed' });
      return null;
    }
    if (!hasAdminRolePermission(role, permission)) {
      res.status(403).json({ error: 'This admin role cannot perform that operation' });
      return null;
    }
    return { userId, role };
  } catch {
    res.status(503).json({ error: 'Admin authorization is temporarily unavailable' });
    return null;
  }
}
function pageArgs(req: Request) {
  const limit = Math.max(1, Math.min(100, Number.parseInt(String(req.query.limit ?? '25'), 10) || 25));
  const offset = Math.max(0, Math.min(100000, Number.parseInt(String(req.query.offset ?? '0'), 10) || 0));
  return { limit, offset };
}
function validReason(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const reason = value.trim();
  return reason.length >= 8 && reason.length <= 500 ? reason : null;
}
function maskEmail(value: string | null | undefined): string | null {
  if (!value) return null;
  const at = value.lastIndexOf('@');
  if (at < 1) return '••••';
  const local = value.slice(0, at);
  const domain = value.slice(at + 1);
  return `${local.slice(0,1)}•••@${domain}`;
}
export function sanitizeAdminDiagnostic(value: unknown): string | null {
  if (typeof value !== 'string' || !value.trim()) return null;
  return value
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '[email]')
    .replace(/\b[0-9a-f]{8}-[0-9a-f-]{27,}\b/gi, '[id]')
    .replace(/\b(?:eyJ[A-Za-z0-9_-]{20,}|[A-Za-z0-9_-]{48,})\b/g, '[secret]')
    .slice(0, 240);
}
async function writeAudit(
  trx: any,
  actor: AdminActor,
  action: string,
  resourceType: string,
  resourceId: string | null,
  reason: string,
  metadata: Record<string, unknown> = {},
) {
  await trx('admin_audit_logs').insert({
    id: randomUUID(),
    actor_user_id: actor.userId,
    action,
    resource_type: resourceType,
    resource_id: resourceId,
    reason,
    request_id: randomUUID(),
    metadata: JSON.stringify(metadata),
  });
}
export function isSupportedAdminConfig(key: string, value: unknown): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  const hasOnly = (keys: string[]) => Object.keys(record).every(name => keys.includes(name));
  if (key === 'maintenance_mode') {
    return hasOnly(['enabled','message']) &&
      typeof record.enabled === 'boolean' &&
      (record.message === undefined || (typeof record.message === 'string' && record.message.length <= 240));
  }
  if (key === 'minimum_app_version') {
    return hasOnly(['versionName','versionCode','force']) &&
      typeof record.versionName === 'string' && /^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?$/.test(record.versionName) &&
      Number.isInteger(record.versionCode) && Number(record.versionCode) >= 1 && typeof record.force === 'boolean';
  }
  if (key === 'feature_flags') {
    const allowed = new Set(['new_onboarding','remote_update_prompt','rules_engine_v2','trip_diagnostics','ios_companion']);
    return Object.entries(record).every(([name, enabled]) => allowed.has(name) && typeof enabled === 'boolean');
  }
  if (key === 'pilot_controls') {
    const allowed = new Set(['android_pilot_enabled','web_gps_preview_enabled','automatic_updates_enabled']);
    return Object.entries(record).every(([name, enabled]) => allowed.has(name) && typeof enabled === 'boolean');
  }
  return false;
}
export function hasAdminRolePermission(role: AdminRole, permission: AdminPermission): boolean {
  return ROLE_PERMISSIONS[role]?.includes(permission) ?? false;
}

// Owner bootstrap is only called by the Vercel server after Supabase getUser(),
// exact UUID allowlist and MFA checks have all passed. Only the first owner can
// be bootstrapped this way; subsequent team changes require an active Owner.
internalAdminRouter.post('/team/bootstrap-owner', async (req, res) => {
  const userId = req.body?.userId;
  if (typeof userId !== 'string' || !isUuid(userId)) {
    return res.status(400).json({ error: 'A valid owner user ID is required' });
  }
  try {
    const alreadyOwner = await db.transaction(async trx => {
      // Serialize first-owner bootstrap attempts to avoid two simultaneous
      // allowlisted requests creating multiple initial owners.
      await trx.raw("SELECT pg_advisory_xact_lock(hashtext('credisafe_admin_owner_bootstrap'))");
      const current = await trx('admin_members').where({ role: 'OWNER', status: 'ACTIVE' }).first();
      if (current) {
        if (current.user_id === userId) return true;
        throw new Error('OWNER_ALREADY_CONFIGURED');
      }

      const existing = await trx('admin_members').where({ user_id: userId }).forUpdate().first();
      if (existing?.status === 'ACTIVE' && existing.role !== 'OWNER') {
        throw new Error('OWNER_ID_ALREADY_ASSIGNED');
      }
      await trx('admin_members').insert({
        user_id: userId, role: 'OWNER', status: 'ACTIVE',
        created_by: userId, reason: 'Initial owner bootstrap from configured server-side allowlist',
      }).onConflict('user_id').merge({
        role: 'OWNER', status: 'ACTIVE', created_by: userId,
        reason: 'Owner bootstrap confirmed by server-side allowlist', updated_at: new Date(),
      });
      if (!existing || existing.status !== 'ACTIVE') {
        await trx('admin_audit_logs').insert({
          id: randomUUID(), actor_user_id: userId, action: 'OWNER_BOOTSTRAPPED',
          resource_type: 'ADMIN_MEMBER', resource_id: userId,
          reason: 'Initial owner bootstrap from configured server-side allowlist',
          request_id: randomUUID(), metadata: JSON.stringify({ role: 'OWNER' }),
        });
      }
      return false;
    });
    return res.json({ success: true, role: 'OWNER', alreadyBootstrapped: alreadyOwner });
  } catch (error) {
    if (error instanceof Error && error.message === 'OWNER_ID_ALREADY_ASSIGNED') {
      return res.status(409).json({ error: 'This identity already has a different active team role' });
    }
    if (error instanceof Error && error.message === 'OWNER_ALREADY_CONFIGURED') {
      return res.status(409).json({ error: 'An owner is already configured; use owner-managed team access' });
    }
    return res.status(503).json({ error: 'Owner bootstrap failed' });
  }
});

internalAdminRouter.get('/team/member/:userId', async (req, res) => {
  const userId = req.params.userId as string;
  if (!isUuid(userId)) return res.status(400).json({ error: 'Invalid user ID' });
  try {
    const member = await db('admin_members')
      .select('user_id','role','status','created_at','updated_at')
      .where({ user_id: userId }).first();
    if (!member) return res.status(404).json({ member: null });
    return res.json({ member });
  } catch {
    return res.status(503).json({ error: 'Admin membership lookup failed' });
  }
});

internalAdminRouter.get('/team', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'team:read');
  if (!actor) return;
  try {
    const members = await db('admin_members').select('user_id','role','status','reason','created_at','updated_at').orderBy('created_at','asc');
    return res.json({ members });
  } catch {
    return res.status(503).json({ error: 'Team directory unavailable' });
  }
});

internalAdminRouter.post('/team', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'team:manage');
  if (!actor) return;
  const userId = req.body?.userId;
  const role = req.body?.role;
  const reason = validReason(req.body?.reason);
  if (typeof userId !== 'string' || !isUuid(userId) || !parseAdminRole(role) || !reason) {
    return res.status(400).json({ error: 'Valid user ID, role and a reason of at least 8 characters are required' });
  }
  if (role === 'OWNER' && actor.role !== 'OWNER') return res.status(403).json({ error: 'Only an Owner can grant Owner access' });
  try {
    const result = await db.transaction(async trx => {
      const existing = await trx('admin_members').where({ user_id: userId }).first();
      if (existing?.status === 'ACTIVE' && existing.role === 'OWNER' && actor.role !== 'OWNER') {
        throw new Error('OWNER_PROTECTED');
      }
      await trx('admin_members').insert({
        user_id: userId, role, status: 'ACTIVE', created_by: actor.userId, reason,
      }).onConflict('user_id').merge({
        role, status: 'ACTIVE', created_by: actor.userId, reason, updated_at: new Date(),
      });
      await writeAudit(trx, actor, 'TEAM_MEMBER_GRANTED', 'ADMIN_MEMBER', userId, reason, { role });
      return { userId, role, status: 'ACTIVE' };
    });
    return res.status(201).json({ member: result });
  } catch (error) {
    if (error instanceof Error && error.message === 'OWNER_PROTECTED') return res.status(403).json({ error: 'Only an Owner can modify an Owner account' });
    return res.status(503).json({ error: 'Unable to update team membership' });
  }
});

internalAdminRouter.patch('/team/:userId', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'team:manage');
  if (!actor) return;
  const userId = req.params.userId as string;
  const role = req.body?.role;
  const status = req.body?.status;
  const reason = validReason(req.body?.reason);
  if (!isUuid(userId) || !reason || !((role === undefined || parseAdminRole(role)) && (status === undefined || status === 'ACTIVE' || status === 'REVOKED'))) {
    return res.status(400).json({ error: 'A valid update and reason of at least 8 characters are required' });
  }
  try {
    const result = await db.transaction(async trx => {
      const target = await trx('admin_members').where({ user_id: userId }).forUpdate().first();
      if (!target) throw new Error('NOT_FOUND');
      if (actor.role !== 'OWNER' && (target.role === 'OWNER' || role === 'OWNER')) throw new Error('OWNER_PROTECTED');
      const nextRole = role ?? target.role;
      const nextStatus = status ?? target.status;
      if (target.role === 'OWNER' && target.status === 'ACTIVE' && (nextRole !== 'OWNER' || nextStatus !== 'ACTIVE')) {
        const owners = await trx('admin_members').where({ role: 'OWNER', status: 'ACTIVE' }).count('* as count').first();
        if (Number(owners?.count ?? 0) <= 1) throw new Error('LAST_OWNER');
      }
      await trx('admin_members').where({ user_id: userId }).update({
        role: nextRole, status: nextStatus, reason, updated_at: new Date(),
      });
      await writeAudit(trx, actor, status === 'REVOKED' ? 'TEAM_MEMBER_REVOKED' : 'TEAM_MEMBER_UPDATED', 'ADMIN_MEMBER', userId, reason, { role: nextRole, status: nextStatus });
      return { userId, role: nextRole, status: nextStatus };
    });
    return res.json({ member: result });
  } catch (error) {
    const code = error instanceof Error ? error.message : '';
    if (code === 'NOT_FOUND') return res.status(404).json({ error: 'Team member not found' });
    if (code === 'OWNER_PROTECTED') return res.status(403).json({ error: 'Only an Owner can modify an Owner account' });
    if (code === 'LAST_OWNER') return res.status(409).json({ error: 'The last active Owner cannot be removed or demoted' });
    return res.status(503).json({ error: 'Unable to update team membership' });
  }
});

internalAdminRouter.get('/users', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'users:read');
  if (!actor) return;
  const { limit, offset } = pageArgs(req);
  const search = typeof req.query.q === 'string' ? req.query.q.trim().slice(0, 120) : '';
  const status = typeof req.query.status === 'string' ? req.query.status : '';
  const accountType = typeof req.query.accountType === 'string' ? req.query.accountType : '';
  try {
    const query = db('users').select('id','name','email','account_type','account_status','created_at','total_xp','total_points','current_level');
    const countQuery = db('users').count('* as count');
    for (const q of [query, countQuery]) {
      if (search) q.where(builder => builder.whereILike('name', `%${search}%`).orWhereILike('email', `%${search}%`).orWhereRaw('id::text ILIKE ?', [`%${search}%`]));
      if (status === 'ACTIVE' || status === 'SUSPENDED') q.where({ account_status: status });
      if (accountType === 'guest' || accountType === 'password') q.where({ account_type: accountType });
    }
    const [users, total] = await Promise.all([query.orderBy('created_at','desc').limit(limit).offset(offset), countQuery.first()]);
    return res.json({
      users: users.map(u => ({
        id: u.id, name: u.name, email: actor.role === 'OWNER' || actor.role === 'ADMIN' ? u.email : maskEmail(u.email),
        accountType: u.account_type, status: u.account_status ?? 'ACTIVE', createdAt: u.created_at,
        totalXp: Number(u.total_xp ?? 0), totalPoints: Number(u.total_points ?? 0), currentLevel: Number(u.current_level ?? 1),
      })),
      pagination: { limit, offset, total: Number(total?.count ?? 0) },
    });
  } catch {
    return res.status(503).json({ error: 'User directory unavailable' });
  }
});

internalAdminRouter.get('/users/:userId', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'users:read');
  if (!actor) return;
  const userId = req.params.userId as string;
  if (!isUuid(userId)) return res.status(400).json({ error: 'Invalid user ID' });
  try {
    const user = await db('users').where({ id: userId })
      .select('id','name','email','account_type','account_status','created_at','total_xp','total_points','current_level','suspended_at','suspension_reason').first();
    if (!user) return res.status(404).json({ error: 'User not found' });
    const [trips, sessions, xp] = await Promise.all([
      db('trips').where({ user_id: userId }).count('* as count').first(),
      db('auth_refresh_sessions').where({ user_id: userId }).whereNull('revoked_at').count('* as count').first(),
      db('xp_ledger').where({ user_id: userId }).sum('delta_xp as total').first(),
    ]);
    return res.json({
      user: {
        id: user.id, name: user.name, email: actor.role === 'OWNER' || actor.role === 'ADMIN' ? user.email : maskEmail(user.email),
        accountType: user.account_type, status: user.account_status ?? 'ACTIVE', createdAt: user.created_at,
        totalXp: Number(user.total_xp ?? 0), totalPoints: Number(user.total_points ?? 0), currentLevel: Number(user.current_level ?? 1),
        suspendedAt: user.suspended_at, suspensionReason: user.suspension_reason,
        counts: { trips: Number(trips?.count ?? 0), activeSessions: Number(sessions?.count ?? 0), ledgerXp: Number(xp?.total ?? 0) },
      },
    });
  } catch {
    return res.status(503).json({ error: 'User profile unavailable' });
  }
});

internalAdminRouter.patch('/users/:userId/status', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'users:write');
  if (!actor) return;
  const userId = req.params.userId as string;
  const status = req.body?.status;
  const reason = validReason(req.body?.reason);
  if (!isUuid(userId) || !['ACTIVE','SUSPENDED'].includes(status) || !reason) {
    return res.status(400).json({ error: 'Valid status and a reason of at least 8 characters are required' });
  }
  try {
    const updated = await db.transaction(async trx => {
      const user = await trx('users').where({ id: userId }).forUpdate().first();
      if (!user) throw new Error('NOT_FOUND');
      const accountUpdate: Record<string, unknown> = {
        account_status: status,
        suspended_at: status === 'SUSPENDED' ? new Date() : null,
        suspended_by: status === 'SUSPENDED' ? actor.userId : null,
        suspension_reason: status === 'SUSPENDED' ? reason : null,
        updated_at: new Date(),
      };
      if (user.account_status !== status) accountUpdate.session_version = trx.raw('COALESCE(session_version, 0) + 1');
      await trx('users').where({ id: userId }).update(accountUpdate);
      let revokedSessions = 0;
      if (status === 'SUSPENDED') {
        const rows = await trx('auth_refresh_sessions').where({ user_id: userId }).whereNull('revoked_at').update({ revoked_at: new Date() });
        revokedSessions = Number(rows ?? 0);
      }
      await writeAudit(trx, actor, status === 'SUSPENDED' ? 'USER_SUSPENDED' : 'USER_RESTORED', 'USER', userId, reason, { revokedSessions });
      return { userId, status, revokedSessions };
    });
    return res.json({ result: updated });
  } catch (error) {
    if (error instanceof Error && error.message === 'NOT_FOUND') return res.status(404).json({ error: 'User not found' });
    return res.status(503).json({ error: 'Unable to update account status' });
  }
});

internalAdminRouter.post('/users/:userId/revoke-sessions', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'users:write');
  if (!actor) return;
  const userId = req.params.userId as string;
  const reason = validReason(req.body?.reason);
  if (!isUuid(userId) || !reason) return res.status(400).json({ error: 'A valid user ID and reason are required' });
  try {
    const result = await db.transaction(async trx => {
      const user = await trx('users').where({ id: userId }).first();
      if (!user) throw new Error('NOT_FOUND');
      await trx('users').where({ id: userId }).update({ session_version: trx.raw('COALESCE(session_version, 0) + 1'), updated_at: new Date() });
      const count = await trx('auth_refresh_sessions').where({ user_id: userId }).whereNull('revoked_at').update({ revoked_at: new Date() });
      await writeAudit(trx, actor, 'USER_SESSIONS_REVOKED', 'USER', userId, reason, { revokedSessions: Number(count ?? 0), accessTokensInvalidated: true });
      return { revokedSessions: Number(count ?? 0) };
    });
    return res.json(result);
  } catch (error) {
    if (error instanceof Error && error.message === 'NOT_FOUND') return res.status(404).json({ error: 'User not found' });
    return res.status(503).json({ error: 'Unable to revoke sessions' });
  }
});

internalAdminRouter.get('/trips', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'trips:read');
  if (!actor) return;
  const { limit, offset } = pageArgs(req);
  const status = typeof req.query.status === 'string' ? req.query.status.slice(0,40) : '';
  const userId = typeof req.query.userId === 'string' && isUuid(req.query.userId) ? req.query.userId : '';
  try {
    const query = db('trips').select('id','user_id','started_at','ended_at','distance_m','duration_ms','safety_score','telemetry_quality','status','trip_classification','eligibility_reason','engine_version','created_at');
    const countQuery = db('trips').count('* as count');
    for (const q of [query,countQuery]) {
      if (status) q.where({ status });
      if (userId) q.where({ user_id: userId });
    }
    const [trips,total] = await Promise.all([query.orderBy('created_at','desc').limit(limit).offset(offset),countQuery.first()]);
    return res.json({
      trips: trips.map(t=>({...t,distanceM:Number(t.distance_m??0),durationMs:Number(t.duration_ms??0),safetyScore:t.safety_score==null?null:Number(t.safety_score),telemetryQuality:t.telemetry_quality==null?null:Number(t.telemetry_quality)})),
      pagination:{limit,offset,total:Number(total?.count??0)},
    });
  } catch {
    return res.status(503).json({ error: 'Trip directory unavailable' });
  }
});

internalAdminRouter.get('/trips/:tripId/diagnostics', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'trips:read');
  if (!actor) return;
  const tripId = req.params.tripId as string;
  if (!isUuid(tripId)) return res.status(400).json({ error: 'Invalid trip ID' });
  try {
    const trip = await db('trips').where({ id: tripId }).first();
    if (!trip) return res.status(404).json({ error: 'Trip not found' });
    const [sync, telemetry, events, xp] = await Promise.all([
      db('sync_audit').where({ trip_id: tripId }).select('sync_status','attempt_count','last_error','synced_at').orderBy('synced_at','desc').limit(12),
      db('telemetry_assets').where({ trip_id: tripId }).select('sample_count','file_size_bytes','checksum_sha256','compression','created_at').first(),
      db('driving_events').where({ trip_id: tripId }).select('timestamp_ms','event_type','severity','confidence','speed_kmh').orderBy('timestamp_ms','asc').limit(250),
      db('xp_ledger').where({ trip_id: tripId }).select('category','points','delta_xp','reason_code','source_type','ruleset_version','engine_version','created_at').orderBy('created_at','asc'),
    ]);
    return res.json({
      trip: {
        id: trip.id, userId: trip.user_id, startedAt: trip.started_at, endedAt: trip.ended_at,
        distanceM: Number(trip.distance_m ?? 0), durationMs: Number(trip.duration_ms ?? 0),
        safetyScore: trip.safety_score == null ? null : Number(trip.safety_score),
        telemetryQuality: trip.telemetry_quality == null ? null : Number(trip.telemetry_quality),
        status: trip.status, classification: trip.trip_classification, eligibilityReason: trip.eligibility_reason,
        engineVersion: trip.engine_version, roadZoneType: trip.road_zone_type, roadContextSource: trip.road_context_source,
        mobilityMode: trip.mobility_mode, mobilityConfidence: trip.mobility_confidence,
      },
      sync: sync.map(s=>({status:s.sync_status,attempts:Number(s.attempt_count??0),diagnostic:sanitizeAdminDiagnostic(s.last_error),recordedAt:s.synced_at})),
      telemetry: telemetry ? { sampleCount:Number(telemetry.sample_count),fileSizeBytes:Number(telemetry.file_size_bytes??0),checksumSha256:telemetry.checksum_sha256,compression:telemetry.compression,createdAt:telemetry.created_at } : null,
      events: events.map(e=>({timestampMs:Number(e.timestamp_ms),type:e.event_type,severity:e.severity,confidence:Number(e.confidence),speedKmh:e.speed_kmh==null?null:Number(e.speed_kmh)})),
      xp: xp.map(x=>({category:x.category,points:Number(x.points),deltaXp:x.delta_xp==null?null:Number(x.delta_xp),reasonCode:x.reason_code,sourceType:x.source_type,rulesetVersion:x.ruleset_version,engineVersion:x.engine_version,createdAt:x.created_at})),
    });
  } catch {
    return res.status(503).json({ error: 'Trip diagnostics unavailable' });
  }
});

internalAdminRouter.get('/xp', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'xp:read');
  if (!actor) return;
  const { limit, offset } = pageArgs(req);
  const userId = typeof req.query.userId === 'string' && isUuid(req.query.userId) ? req.query.userId : '';
  try {
    const query = db('xp_ledger').select('id','user_id','trip_id','category','points','delta_xp','reason','reason_code','source_type','ruleset_version','engine_version','created_at');
    const countQuery = db('xp_ledger').count('* as count');
    if (userId) { query.where({user_id:userId}); countQuery.where({user_id:userId}); }
    const [rows,total] = await Promise.all([query.orderBy('created_at','desc').limit(limit).offset(offset),countQuery.first()]);
    return res.json({ entries:rows, pagination:{limit,offset,total:Number(total?.count??0)} });
  } catch {
    return res.status(503).json({ error: 'XP ledger unavailable' });
  }
});

internalAdminRouter.get('/audit', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'audit:read');
  if (!actor) return;
  const { limit, offset } = pageArgs(req);
  try {
    const [entries,total] = await Promise.all([
      db('admin_audit_logs').select('id','actor_user_id','action','resource_type','resource_id','reason','metadata','created_at').orderBy('created_at','desc').limit(limit).offset(offset),
      db('admin_audit_logs').count('* as count').first(),
    ]);
    return res.json({ entries, pagination:{limit,offset,total:Number(total?.count??0)} });
  } catch {
    return res.status(503).json({ error: 'Audit history unavailable' });
  }
});

internalAdminRouter.get('/config', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'config:read');
  if (!actor) return;
  try {
    const configs = await db('admin_config').select('config_key','config_value','version','updated_by','reason','updated_at').orderBy('config_key','asc');
    return res.json({ configs });
  } catch {
    return res.status(503).json({ error: 'Configuration unavailable' });
  }
});

internalAdminRouter.put('/config/:key', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'config:write');
  if (!actor) return;
  const key = req.params.key as string;
  const value = req.body?.value;
  const reason = validReason(req.body?.reason);
  const expectedVersion = req.body?.expectedVersion;
  if (!reason || !isSupportedAdminConfig(key,value) || (expectedVersion !== undefined && (!Number.isInteger(expectedVersion) || expectedVersion < 0))) {
    return res.status(400).json({ error: 'Unsupported configuration or invalid reason/value' });
  }
  try {
    const saved = await db.transaction(async trx => {
      const current = await trx('admin_config').where({config_key:key}).forUpdate().first();
      const currentVersion = Number(current?.version ?? 0);
      if (expectedVersion !== undefined && expectedVersion !== currentVersion) throw new Error('VERSION_CONFLICT');
      const nextVersion = currentVersion + 1;
      await trx('admin_config').insert({
        config_key:key,config_value:JSON.stringify(value),version:nextVersion,updated_by:actor.userId,reason,
      }).onConflict('config_key').merge({
        config_value:JSON.stringify(value),version:nextVersion,updated_by:actor.userId,reason,updated_at:new Date(),
      });
      await writeAudit(trx,actor,'CONFIG_UPDATED','CONFIG',key,reason,{version:nextVersion});
      return {key,value,version:nextVersion,updatedAt:new Date().toISOString()};
    });
    return res.json({ config:saved });
  } catch (error) {
    if (error instanceof Error && error.message === 'VERSION_CONFLICT') return res.status(409).json({error:'Configuration changed elsewhere. Refresh before saving.'});
    return res.status(503).json({error:'Configuration update failed'});
  }
});

internalAdminRouter.get('/system', async (req, res) => {
  const actor = await authorizeAdmin(req, res, 'system:read');
  if (!actor) return;
  try {
    await db.raw('SELECT 1');
    const checks = await Promise.all([
      db('users').count('* as count').first(),
      db('trips').count('* as count').first(),
      db('admin_members').where({status:'ACTIVE'}).count('* as count').first(),
    ]);
    return res.json({
      checkedAt:new Date().toISOString(),
      services:{render:{status:'ok',message:'Admin API is responding'},neon:{status:'ok',message:'Database query succeeded'},adminSchema:{status:'ok',message:'Admin schema is readable'}},
      counts:{users:Number(checks[0]?.count??0),trips:Number(checks[1]?.count??0),activeAdmins:Number(checks[2]?.count??0)},
    });
  } catch {
    return res.status(503).json({error:'System health check failed'});
  }
});
