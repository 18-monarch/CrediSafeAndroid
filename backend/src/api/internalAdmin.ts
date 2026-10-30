import {
  createHash, createHmac, randomUUID, timingSafeEqual,
} from 'node:crypto';
import { Router, type Request, type Response, type NextFunction } from 'express';
import type { Knex } from 'knex';
import { validate as isUuid } from 'uuid';
import { calculateLevelFromTotalXp } from '../services/xpEngineService.js';
import db from '../db/knex.js';

type AdminRole = 'OWNER' | 'ADMIN' | 'SUPPORT' | 'ANALYST' | 'AUDITOR';
type AdminRequest = Request & { admin?: { userId: string; role: AdminRole } };
const ROLES: AdminRole[] = ['OWNER', 'ADMIN', 'SUPPORT', 'ANALYST', 'AUDITOR'];
const USER_READ_ROLES: AdminRole[] = ['OWNER', 'ADMIN', 'SUPPORT'];
const TRIP_READ_ROLES: AdminRole[] = ['OWNER', 'ADMIN', 'SUPPORT', 'ANALYST', 'AUDITOR'];

export const internalAdminRouter = Router();

export function permittedInternalSecret(presented: unknown, configured: string | undefined): boolean {
  if (typeof presented !== 'string' || typeof configured !== 'string' ||
      configured.length < 32 || presented.length !== configured.length) return false;
  const given = Buffer.from(presented, 'utf8');
  const expected = Buffer.from(configured, 'utf8');
  return given.length === expected.length && timingSafeEqual(given, expected);
}

function listIds(value?: string): Set<string> {
  return new Set((value || '').split(',').map(v => v.trim().toLowerCase()).filter(Boolean));
}

function safeCount(value: unknown): number {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
}

function reasonText(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const valueTrimmed = value.trim();
  return valueTrimmed.length >= 8 && valueTrimmed.length <= 500 ? valueTrimmed : null;
}

function legacyOverviewAuth(req: Request, res: Response, next: NextFunction) {
  const configured = process.env.CREDISAFE_ADMIN_BRIDGE_SECRET;
  if (!configured || configured.length < 32) {
    return res.status(503).json({ error: 'Team monitoring is not configured' });
  }
  if (!permittedInternalSecret(req.header('x-credisafe-admin-bridge'), configured)) {
    return res.status(403).json({ error: 'Forbidden' });
  }
  res.set('Cache-Control', 'no-store, private');
  next();
}

async function signedAdminAuth(req: AdminRequest, res: Response, next: NextFunction) {
  const secret = process.env.CREDISAFE_ADMIN_BRIDGE_SECRET;
  const userId = req.header('x-admin-user-id') || '';
  const timestamp = req.header('x-admin-timestamp') || '';
  const signature = req.header('x-admin-signature') || '';
  const bootstrapOwner = req.header('x-admin-bootstrap-owner') === 'true';
  if (!secret || secret.length < 32 || !isUuid(userId) ||
      !/^\d{13}$/.test(timestamp) || !/^[a-f0-9]{64}$/i.test(signature)) {
    return res.status(401).json({ error: 'Invalid admin assertion' });
  }

  const sentAt = Number(timestamp);
  if (!Number.isFinite(sentAt) || Math.abs(Date.now() - sentAt) > 60_000) {
    return res.status(401).json({ error: 'Expired admin assertion' });
  }

  const body = req.body && typeof req.body === 'object' ? req.body : {};
  const bodyHash = createHash('sha256').update(JSON.stringify(body)).digest('hex');
  const payload = [
    req.method.toUpperCase(), req.path, timestamp, userId,
    bootstrapOwner ? '1' : '0', bodyHash,
  ].join('\n');
  const expected = createHmac('sha256', secret).update(payload).digest('hex');
  const supplied = Buffer.from(signature.toLowerCase(), 'hex');
  const expectedBuffer = Buffer.from(expected, 'hex');
  if (supplied.length !== expectedBuffer.length || !timingSafeEqual(supplied, expectedBuffer)) {
    return res.status(401).json({ error: 'Invalid admin signature' });
  }

  try {
    let member = await db('admin_members').where({ user_id: userId }).first();
    // One-time owner bootstrap: Vercel signs this claim only for an exact UUID
    // in its server-only CREDISAFE_ADMIN_USER_IDS allowlist.
    if (!member && bootstrapOwner && req.path === '/authz') {
      await db('admin_members').insert({
        user_id: userId,
        role: 'OWNER',
        status: 'ACTIVE',
        created_by: userId,
        reason: 'Initial owner bootstrap',
        created_at: new Date(),
        updated_at: new Date(),
      }).onConflict('user_id').ignore();
      member = await db('admin_members').where({ user_id: userId }).first();
      if (member) {
        await db('admin_audit_logs').insert({
          id: randomUUID(),
          actor_user_id: userId,
          action: 'TEAM_OWNER_BOOTSTRAP',
          resource_type: 'ADMIN_MEMBER',
          resource_id: userId,
          reason: 'Initial owner bootstrap',
          metadata: { role: 'OWNER' },
        });
      }
    }
    if (!member || member.status !== 'ACTIVE' || !ROLES.includes(member.role)) {
      return res.status(403).json({ error: 'Admin access is not enabled for this account' });
    }
    req.admin = { userId, role: member.role as AdminRole };
    res.set('Cache-Control', 'no-store, private');
    next();
  } catch {
    return res.status(503).json({ error: 'Admin authorization service unavailable' });
  }
}

function requireRoles(req: AdminRequest, res: Response, roles: AdminRole[]): boolean {
  if (!req.admin || !roles.includes(req.admin.role)) {
    res.status(403).json({ error: 'Your admin role does not permit this operation' });
    return false;
  }
  return true;
}

async function audit(
  trx: Knex.Transaction,
  actor: string,
  action: string,
  resourceType: string,
  resourceId: string | null,
  reason: string,
  metadata: Record<string, unknown> = {},
  requestId = randomUUID(),
) {
  await trx('admin_audit_logs').insert({
    id: randomUUID(),
    actor_user_id: actor,
    action,
    resource_type: resourceType,
    resource_id: resourceId,
    reason,
    request_id: requestId,
    metadata,
    created_at: new Date(),
  });
}

async function getOverview() {
  const [users, activeUsers, suspendedUsers, trips, events, telemetry, syncIssues, recentUsers, statuses, eventTypes] =
    await Promise.all([
      db('users').count('* as count').first(),
      db('users').where({ account_status: 'ACTIVE' }).count('* as count').first(),
      db('users').where({ account_status: 'SUSPENDED' }).count('* as count').first(),
      db('trips').count('* as count').first(),
      db('driving_events').count('* as count').first(),
      db('telemetry_assets').count('* as count').first(),
      db('sync_audit').whereNotIn('sync_status', ['SUCCESS', 'SYNCED', 'CONFIRMED'])
        .count('* as count').first(),
      db('users').select('id', 'name', 'account_type', 'account_status', 'created_at', 'total_xp', 'current_level')
        .orderBy('created_at', 'desc').limit(8),
      db('trips').select('status').count('* as count').groupBy('status'),
      db('driving_events').select('event_type').count('* as count').groupBy('event_type'),
    ]);
  return {
    source: 'android-neon',
    fetchedAt: new Date().toISOString(),
    totals: {
      users: safeCount(users?.count),
      activeUsers: safeCount(activeUsers?.count),
      suspendedUsers: safeCount(suspendedUsers?.count),
      trips: safeCount(trips?.count),
      events: safeCount(events?.count),
      telemetryUploads: safeCount(telemetry?.count),
      syncIssues: safeCount(syncIssues?.count),
    },
    tripsByStatus: statuses.map((v: any) => ({ status: v.status, count: safeCount(v.count) })),
    eventsByType: eventTypes.map((v: any) => ({ type: v.event_type, count: safeCount(v.count) })),
    recentUsers: recentUsers.map((u: any) => ({
      id: u.id, name: u.name, accountType: u.account_type,
      accountStatus: u.account_status, createdAt: u.created_at,
      totalXp: safeCount(u.total_xp), level: safeCount(u.current_level),
    })),
  };
}

internalAdminRouter.get('/overview', legacyOverviewAuth, async (_req, res) => {
  try {
    res.json(await getOverview());
  } catch {
    res.status(503).json({ error: 'Monitoring metrics temporarily unavailable' });
  }
});

const adminRouter = Router();
adminRouter.use(signedAdminAuth);

adminRouter.get('/authz', (req: AdminRequest, res) => {
  res.json({ userId: req.admin!.userId, role: req.admin!.role, mfaEnforced: true });
});

adminRouter.get('/overview', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ROLES)) return;
  try { res.json(await getOverview()); }
  catch { res.status(503).json({ error: 'Overview metrics temporarily unavailable' }); }
});

adminRouter.get('/users', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, USER_READ_ROLES)) return;
  const q = String(req.query.q || '').trim().slice(0, 100);
  const status = String(req.query.status || '').toUpperCase();
  const limit = Math.min(100, Math.max(1, Number(req.query.limit) || 50));
  try {
    const query = db('users')
      .select('id', 'name', 'email', 'account_type', 'account_status', 'created_at',
        'updated_at', 'total_xp', 'total_points', 'current_level', 'email_verified_at')
      .orderBy('created_at', 'desc').limit(limit);
    if (q) query.where(builder => builder.whereILike('name', `%${q}%`).orWhereILike('email', `%${q}%`).orWhereRaw('id::text ILIKE ?', [`%${q}%`]));
    if (status === 'ACTIVE' || status === 'SUSPENDED') query.where({ account_status: status });
    const rows = await query;
    res.json({ users: rows, count: rows.length });
  } catch { res.status(503).json({ error: 'User directory unavailable' }); }
});

adminRouter.get('/users/:userId', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, USER_READ_ROLES)) return;
  const { userId } = req.params;
  if (!isUuid(userId)) return res.status(400).json({ error: 'Invalid user ID' });
  try {
    const user = await db('users').select(
      'id', 'name', 'email', 'account_type', 'account_status', 'created_at',
      'updated_at', 'total_xp', 'total_points', 'current_level', 'email_verified_at',
      'suspended_at', 'suspension_reason',
    ).where({ id: userId }).first();
    if (!user) return res.status(404).json({ error: 'User not found' });
    const [trips, sessions, recentTrips, ledger] = await Promise.all([
      db('trips').where({ user_id: userId }).count('* as count').first(),
      db('auth_refresh_sessions').where({ user_id: userId }).whereNull('revoked_at')
        .where('expires_at', '>', new Date()).count('* as count').first(),
      db('trips').select('id', 'started_at', 'distance_m', 'duration_ms', 'status', 'safety_score', 'engine_version')
        .where({ user_id: userId }).orderBy('started_at', 'desc').limit(10),
      db('xp_ledger').select('id', 'delta_xp', 'points', 'reason_code', 'reason', 'ruleset_version', 'created_at')
        .where({ user_id: userId }).orderBy('created_at', 'desc').limit(10),
    ]);
    res.json({
      user, tripCount: safeCount(trips?.count), activeSessions: safeCount(sessions?.count),
      recentTrips, recentXp: ledger,
    });
  } catch { res.status(503).json({ error: 'User details unavailable' }); }
});

adminRouter.post('/users/:userId/suspend', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER', 'ADMIN'])) return;
  const { userId } = req.params;
  const reason = reasonText(req.body?.reason);
  if (!isUuid(userId) || !reason) return res.status(400).json({ error: 'Valid user ID and a reason of 8–500 characters are required' });
  try {
    await db.transaction(async trx => {
      const user = await trx('users').where({ id: userId }).forUpdate().first();
      if (!user) throw Object.assign(new Error('User not found'), { status: 404 });
      if (user.account_status === 'SUSPENDED') return;
      await trx('users').where({ id: userId }).update({
        account_status: 'SUSPENDED', suspended_at: new Date(),
        suspension_reason: reason, updated_at: new Date(),
      });
      await trx('auth_refresh_sessions').where({ user_id: userId }).whereNull('revoked_at')
        .update({ revoked_at: new Date() });
      await audit(trx, req.admin!.userId, 'USER_SUSPENDED', 'USER', userId, reason);
    });
    res.json({ success: true, accountStatus: 'SUSPENDED' });
  } catch (error: any) { res.status(error.status || 503).json({ error: error.status ? error.message : 'Unable to suspend account' }); }
});

adminRouter.post('/users/:userId/restore', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER', 'ADMIN'])) return;
  const { userId } = req.params;
  const reason = reasonText(req.body?.reason);
  if (!isUuid(userId) || !reason) return res.status(400).json({ error: 'Valid user ID and a reason of 8–500 characters are required' });
  try {
    await db.transaction(async trx => {
      const user = await trx('users').where({ id: userId }).forUpdate().first();
      if (!user) throw Object.assign(new Error('User not found'), { status: 404 });
      await trx('users').where({ id: userId }).update({
        account_status: 'ACTIVE', suspended_at: null, suspension_reason: null, updated_at: new Date(),
      });
      await audit(trx, req.admin!.userId, 'USER_RESTORED', 'USER', userId, reason);
    });
    res.json({ success: true, accountStatus: 'ACTIVE' });
  } catch (error: any) { res.status(error.status || 503).json({ error: error.status ? error.message : 'Unable to restore account' }); }
});

adminRouter.post('/users/:userId/revoke-sessions', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER', 'ADMIN', 'SUPPORT'])) return;
  const { userId } = req.params;
  const reason = reasonText(req.body?.reason);
  if (!isUuid(userId) || !reason) return res.status(400).json({ error: 'Valid user ID and a reason of 8–500 characters are required' });
  try {
    const count = await db.transaction(async trx => {
      const rows = await trx('auth_refresh_sessions').where({ user_id: userId }).whereNull('revoked_at')
        .update({ revoked_at: new Date() });
      await audit(trx, req.admin!.userId, 'USER_SESSIONS_REVOKED', 'USER', userId, reason, { sessionsRevoked: rows });
      return rows;
    });
    res.json({ success: true, sessionsRevoked: count });
  } catch { res.status(503).json({ error: 'Unable to revoke sessions' }); }
});

adminRouter.get('/trips', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, TRIP_READ_ROLES)) return;
  const q = String(req.query.q || '').trim().slice(0, 100);
  const status = String(req.query.status || '').trim().toUpperCase();
  const limit = Math.min(100, Math.max(1, Number(req.query.limit) || 50));
  try {
    const query = db('trips as t').leftJoin('users as u', 'u.id', 't.user_id')
      .select('t.id', 't.user_id', 'u.name as user_name', 't.started_at', 't.ended_at',
        't.distance_m', 't.duration_ms', 't.status', 't.trip_classification',
        't.eligibility_reason', 't.safety_score', 't.telemetry_quality', 't.engine_version')
      .orderBy('t.started_at', 'desc').limit(limit);
    if (q) query.where(builder => builder.whereRaw('t.id::text ILIKE ?', [`%${q}%`]).orWhereRaw('t.user_id::text ILIKE ?', [`%${q}%`]).orWhereILike('u.name', `%${q}%`));
    if (status) query.where('t.status', status);
    const rows = await query;
    res.json({ trips: rows, count: rows.length });
  } catch { res.status(503).json({ error: 'Trip directory unavailable' }); }
});

adminRouter.get('/trips/:tripId', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, TRIP_READ_ROLES)) return;
  const { tripId } = req.params;
  if (!isUuid(tripId)) return res.status(400).json({ error: 'Invalid trip ID' });
  try {
    const trip = await db('trips as t').leftJoin('users as u', 'u.id', 't.user_id')
      .select('t.*', 'u.name as user_name', 'u.account_type as user_account_type')
      .where('t.id', tripId).first();
    if (!trip) return res.status(404).json({ error: 'Trip not found' });
    const [metrics, eventGroups, ledger, sync] = await Promise.all([
      db('trip_metrics').where({ trip_id: tripId }).first(),
      db('driving_events').select('event_type', 'severity').count('* as count')
        .where({ trip_id: tripId }).groupBy('event_type', 'severity'),
      db('xp_ledger').select('delta_xp', 'points', 'reason_code', 'reason', 'ruleset_version', 'decision_metadata', 'created_at')
        .where({ trip_id: tripId }).orderBy('created_at', 'desc'),
      db('sync_audit').select('sync_status', 'attempt_count', 'last_error', 'synced_at')
        .where({ trip_id: tripId }).orderBy('synced_at', 'desc').limit(10),
    ]);
    // This is a diagnostic view, not a raw route/telemetry export.
    res.json({ trip, metrics: metrics || null, eventGroups, xpLedger: ledger, syncHistory: sync });
  } catch { res.status(503).json({ error: 'Trip diagnostics unavailable' }); }
});

adminRouter.get('/xp/ledger', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER', 'ADMIN', 'ANALYST', 'AUDITOR'])) return;
  const userId = String(req.query.userId || '');
  const limit = Math.min(100, Math.max(1, Number(req.query.limit) || 50));
  if (userId && !isUuid(userId)) return res.status(400).json({ error: 'Invalid user ID' });
  try {
    const query = db('xp_ledger as x').leftJoin('users as u', 'u.id', 'x.user_id')
      .select('x.id', 'x.user_id', 'u.name as user_name', 'x.trip_id', 'x.category',
        'x.delta_xp', 'x.points', 'x.reason', 'x.reason_code', 'x.ruleset_version', 'x.source_type', 'x.created_at')
      .orderBy('x.created_at', 'desc').limit(limit);
    if (userId) query.where('x.user_id', userId);
    res.json({ entries: await query });
  } catch { res.status(503).json({ error: 'XP ledger unavailable' }); }
});

adminRouter.post('/xp/adjust', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER'])) return;
  const userId = String(req.body?.userId || '');
  const delta = Number(req.body?.delta);
  const reason = reasonText(req.body?.reason);
  const requestId = isUuid(req.body?.requestId) ? req.body.requestId : randomUUID();
  if (!isUuid(userId) || !Number.isInteger(delta) || delta === 0 || Math.abs(delta) > 500 || !reason) {
    return res.status(400).json({ error: 'Valid user, non-zero XP adjustment up to 500, and a reason of 8–500 characters are required' });
  }
  try {
    const result = await db.transaction(async trx => {
      const existing = await trx('xp_ledger').where({
        user_id: userId, source_type: 'ADMIN_ADJUSTMENT', source_id: requestId,
        ruleset_version: 'ADMIN_ADJUSTMENT_V1',
      }).first();
      if (existing) return { duplicate: true, newTotalXp: null };
      const user = await trx('users').where({ id: userId }).forUpdate().first();
      if (!user) throw Object.assign(new Error('User not found'), { status: 404 });
      const previous = safeCount(user.total_xp);
      const next = previous + delta;
      if (next < 0) throw Object.assign(new Error('XP balance cannot become negative'), { status: 400 });
      const level = calculateLevelFromTotalXp(next);
      await trx('xp_ledger').insert({
        user_id: userId, trip_id: null, category: 'ADMIN_ADJUSTMENT',
        points: delta, delta_xp: delta, reason: reason,
        engine_version: 'ADMIN_ADJUSTMENT_V1', ruleset_version: 'ADMIN_ADJUSTMENT_V1',
        source_type: 'ADMIN_ADJUSTMENT', source_id: requestId, reason_code: 'ADMIN_ADJUSTMENT',
        decision_metadata: JSON.stringify({ actor: req.admin!.userId, previousTotalXp: previous, nextTotalXp: next, requestId }),
      });
      await trx('users').where({ id: userId }).update({
        total_xp: next, current_level: level.currentLevel, updated_at: new Date(),
      });
      await audit(trx, req.admin!.userId, 'XP_ADJUSTED', 'USER', userId, reason,
        { delta, previousTotalXp: previous, nextTotalXp: next }, requestId);
      return { duplicate: false, previousTotalXp: previous, newTotalXp: next, level: level.currentLevel };
    });
    res.json({ success: true, ...result });
  } catch (error: any) { res.status(error.status || 503).json({ error: error.status ? error.message : 'Unable to adjust XP' }); }
});

const CONFIG_KEYS = new Set([
  'pilot_mode', 'feature_flags', 'maintenance_banner',
  'minimum_android_version', 'xp_ruleset_version',
]);

function validConfigValue(key: string, value: unknown): boolean {
  if (!CONFIG_KEYS.has(key)) return false;
  if (key === 'pilot_mode') return typeof value === 'boolean';
  if (key === 'feature_flags') {
    return !!value && typeof value === 'object' && !Array.isArray(value) &&
      Object.keys(value as object).length <= 50 &&
      Object.values(value as Record<string, unknown>).every(v => typeof v === 'boolean');
  }
  if (key === 'maintenance_banner') return typeof value === 'string' && value.length <= 180;
  if (key === 'minimum_android_version') return typeof value === 'string' && /^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?$/.test(value);
  if (key === 'xp_ruleset_version') return value === 'XP_RULESET_V1';
  return false;
}

adminRouter.get('/config', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER', 'ADMIN', 'ANALYST', 'AUDITOR'])) return;
  try {
    const rows = await db('admin_config').select('config_key', 'config_value', 'version', 'reason', 'updated_at')
      .orderBy('config_key');
    res.json({ configuration: rows, supportedKeys: Array.from(CONFIG_KEYS) });
  } catch { res.status(503).json({ error: 'Configuration store unavailable' }); }
});

adminRouter.post('/config', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER', 'ADMIN'])) return;
  const key = String(req.body?.key || '');
  const reason = reasonText(req.body?.reason);
  const value = req.body?.value;
  if (!reason || !validConfigValue(key, value)) {
    return res.status(400).json({ error: 'Unsupported configuration key or invalid value; a reason is required' });
  }
  try {
    const result = await db.transaction(async trx => {
      const previous = await trx('admin_config').where({ config_key: key }).forUpdate().first();
      const nextVersion = safeCount(previous?.version) + 1;
      await trx('admin_config').insert({
        config_key: key, config_value: JSON.stringify(value), version: nextVersion,
        updated_by: req.admin!.userId, reason, updated_at: new Date(),
      }).onConflict('config_key').merge();
      await audit(trx, req.admin!.userId, 'CONFIG_UPDATED', 'CONFIG', key, reason,
        { version: nextVersion, previousValue: previous?.config_value ?? null, newValue: value });
      return { key, version: nextVersion };
    });
    res.json({ success: true, ...result });
  } catch { res.status(503).json({ error: 'Unable to update configuration' }); }
});

adminRouter.get('/team', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER'])) return;
  try {
    const members = await db('admin_members as m').leftJoin('admin_audit_logs as a', function () {
      this.on('a.actor_user_id', '=', 'm.user_id');
    }).select('m.user_id', 'm.role', 'm.status', 'm.reason', 'm.created_at', 'm.updated_at')
      .count('a.id as actions_count').groupBy('m.user_id', 'm.role', 'm.status', 'm.reason', 'm.created_at', 'm.updated_at')
      .orderBy('m.created_at', 'asc');
    res.json({ members });
  } catch { res.status(503).json({ error: 'Team directory unavailable' }); }
});

adminRouter.post('/team', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER'])) return;
  const userId = String(req.body?.userId || '');
  const role = String(req.body?.role || '').toUpperCase() as AdminRole;
  const reason = reasonText(req.body?.reason);
  const status = req.body?.status === 'REVOKED' ? 'REVOKED' : 'ACTIVE';
  if (!isUuid(userId) || !ROLES.includes(role) || !reason) {
    return res.status(400).json({ error: 'Valid auth UUID, role, and reason of 8–500 characters are required' });
  }
  try {
    const result = await db.transaction(async trx => {
      const existing = await trx('admin_members').where({ user_id: userId }).forUpdate().first();
      if (existing?.role === 'OWNER' && status === 'REVOKED') {
        const owners = await trx('admin_members').where({ role: 'OWNER', status: 'ACTIVE' }).count('* as count').first();
        if (safeCount(owners?.count) <= 1) throw Object.assign(new Error('The last active owner cannot be revoked'), { status: 400 });
      }
      await trx('admin_members').insert({
        user_id: userId, role, status, created_by: req.admin!.userId,
        reason, created_at: new Date(), updated_at: new Date(),
      }).onConflict('user_id').merge({
        role, status, created_by: req.admin!.userId, reason, updated_at: new Date(),
      });
      await audit(trx, req.admin!.userId, status === 'ACTIVE' ? 'TEAM_MEMBER_UPSERTED' : 'TEAM_MEMBER_REVOKED',
        'ADMIN_MEMBER', userId, reason, { role, status });
      return { userId, role, status };
    });
    res.json({ success: true, member: result });
  } catch (error: any) { res.status(error.status || 503).json({ error: error.status ? error.message : 'Unable to update team member' }); }
});

adminRouter.get('/audit', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ['OWNER', 'ADMIN', 'AUDITOR'])) return;
  const limit = Math.min(200, Math.max(1, Number(req.query.limit) || 100));
  try {
    const logs = await db('admin_audit_logs').select(
      'id', 'actor_user_id', 'action', 'resource_type', 'resource_id', 'reason', 'metadata', 'created_at',
    ).orderBy('created_at', 'desc').limit(limit);
    res.json({ logs });
  } catch { res.status(503).json({ error: 'Audit history unavailable' }); }
});

adminRouter.get('/releases', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ROLES)) return;
  try {
    const response = await fetch('https://api.github.com/repos/18-monarch/CrediSafeAndroid/releases?per_page=10', {
      headers: { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    });
    if (!response.ok) return res.status(503).json({ error: 'GitHub release information unavailable' });
    const releases = await response.json() as any[];
    res.json({ releases: releases.map(r => ({
      tag: r.tag_name, title: r.name, draft: r.draft, prerelease: r.prerelease,
      publishedAt: r.published_at, url: r.html_url,
      assets: (r.assets || []).map((a: any) => ({
        name: a.name, size: a.size, downloadUrl: a.browser_download_url,
      })),
    })) });
  } catch { res.status(503).json({ error: 'Release information unavailable' }); }
});

adminRouter.get('/health', async (req: AdminRequest, res) => {
  if (!requireRoles(req, res, ROLES)) return;
  try {
    const dbResult = await db.raw('SELECT 1 AS ok');
    const render = { status: 'ok', checkedAt: new Date().toISOString() };
    const database = { status: dbResult.rows?.[0]?.ok === 1 ? 'ok' : 'degraded' };
    res.json({ services: { render, neon: database }, checkedAt: new Date().toISOString() });
  } catch {
    res.status(503).json({ services: { render: { status: 'degraded' }, neon: { status: 'down' } } });
  }
});

internalAdminRouter.use('/admin', adminRouter);
