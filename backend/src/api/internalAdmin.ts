import { Router, type Request, type Response, type NextFunction } from 'express';
import { timingSafeEqual } from 'node:crypto';
import db from '../db/knex.js';

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
