import { Router } from 'express';
import { AuthError, createSession, refreshSession, revokeSession } from '../auth/authService.js';
import { authMiddleware, AuthRequest } from '../middleware/authMiddleware.js';
import * as tripService from '../services/tripService.js';
import * as telemetryService from '../services/telemetryService.js';
import db from '../db/knex.js';
import { getRoadContext } from '../services/roadContextService.js';
import { issueRoadLimitProof } from '../services/roadLimitProof.js';

import { getLiveTrips, updateLiveTrip } from '../sync/liveStore.js';

const router = Router();

// 1. Auth
router.post('/auth/session', async (req, res) => {
  try {
    const { deviceId, email, password, name } = req.body || {};
    if (!deviceId) return res.status(400).json({ error: { code: 'invalid_request', message: 'deviceId is required' } });
    const session = await createSession(deviceId as string, email as string, password as string, name as string);
    res.json(session);
  } catch (err: any) {
    if (err instanceof AuthError) {
      // Safe observability: log only a fixed error code and HTTP status.
      // Never log request body, email, password, access/refresh tokens or device ID.
      console.info('[auth/session] rejected', {
        status: err.status,
        code: err.code,
      });
      return res.status(err.status).json({ error: { code: err.code, message: err.message } });
    }

    // Diagnostic metadata only. Never log request bodies, passwords, email
    // addresses, access tokens, or refresh tokens.
    console.error('[auth/session] unexpected failure', {
      name: err instanceof Error ? err.name : typeof err,
      code: typeof err?.code === 'string' ? err.code : undefined,
      constraint: typeof err?.constraint === 'string' ? err.constraint : undefined,
      table: typeof err?.table === 'string' ? err.table : undefined,
    });
    return res.status(503).json({
      error: { code: 'session_unavailable', message: 'Authentication is temporarily unavailable. Please try again shortly.' }
    });
  }
});

// 1b. Renewable sessions: tokens rotate and are bound to their device.
router.post('/auth/refresh', async (req, res) => {
  try {
    const { deviceId, refreshToken } = req.body || {};
    res.json(await refreshSession(deviceId, refreshToken));
  } catch (err: any) {
    if (err instanceof AuthError) {
      return res.status(err.status).json({ error: { code: err.code, message: err.message } });
    }
    res.status(503).json({ error: { code: 'session_unavailable', message: 'Please try again later.' } });
  }
});

router.post('/auth/logout', async (req, res) => {
  try {
    const { deviceId, refreshToken } = req.body || {};
    await revokeSession(deviceId, refreshToken);
    res.json({ success: true });
  } catch {
    res.status(503).json({ error: { code: 'session_unavailable', message: 'Please try again later.' } });
  }
});

// 1c. Versioned, allow-listed remote configuration for signed-in app clients.
// Only published values from the Operations Center are returned; no secrets are stored here.
router.get('/config', authMiddleware, async (_req: AuthRequest, res) => {
  try {
    const rows = await db('admin_config')
      .select('config_key', 'config_value', 'version', 'updated_at')
      .orderBy('config_key');
    const config = Object.fromEntries(rows.map(row => [row.config_key, row.config_value]));
    const version = rows.reduce((max, row) => Math.max(max, Number(row.version) || 0), 0);
    res.set('Cache-Control', 'private, no-store');
    res.json({
      version,
      fetchedAt: new Date().toISOString(),
      config,
    });
  } catch {
    res.status(503).json({
      error: { code: 'remote_config_unavailable', message: 'Configuration is temporarily unavailable.' },
    });
  }
});

// 2. Trips
router.post('/trips', authMiddleware, async (req: AuthRequest, res) => {
  try {
    const result = await tripService.createOrUpdateTrip(req.userId!, req.body);
    res.json(result);
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});

// Real-time fallback
router.post('/live/trips/:tripId/frames', authMiddleware, async (req: AuthRequest, res) => {
  try {
    const tripId = req.params.tripId as string;
    if (!tripId) throw new Error('tripId is required');
    await updateLiveTrip(tripId, req.userId!, req.body);
    res.json({ success: true });
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});

router.post('/trips/:tripId/events', authMiddleware, async (req: AuthRequest, res) => {
  try {
    const tripId = req.params.tripId as string;
    if (!tripId) throw new Error('tripId is required');
    await tripService.uploadEvents(tripId, req.userId!, req.body);
    res.json({ success: true, message: 'Events uploaded' });
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});

router.post('/trips/:tripId/telemetry', authMiddleware, async (req: AuthRequest, res) => {
  try {
    const tripId = req.params.tripId as string;
    if (!tripId || req.body?.tripId !== tripId) {
      return res.status(400).json({ error: { code: 'trip_id_mismatch', message: 'Path and payload trip IDs must match.' } });
    }
    const result = await telemetryService.processTelemetry(req.userId!, req.body);
    res.json(result);
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});

router.post('/trips/:tripId/complete', authMiddleware, async (req: AuthRequest, res) => {
  try {
    const tripId = req.params.tripId as string;
    if (!tripId) throw new Error('tripId is required');
    const result = await tripService.completeTrip(tripId, req.userId!);
    res.json(result);
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});

router.get('/trips/:tripId', authMiddleware, async (req: AuthRequest, res) => {
  try {
    const tripId = req.params.tripId as string;
    if (!tripId) throw new Error('tripId is required');
    const trip = await tripService.getTripDetails(tripId, req.userId!);
    if (!trip) return res.status(404).json({ error: { code: 'not_found', message: 'Trip not found' } });
    res.json(trip);
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});

router.get('/trips', authMiddleware, async (req: AuthRequest, res) => {
  try {
    const trips = await tripService.listTrips(req.userId!);
    res.json(trips);
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});


// Server-side OSM/Valhalla road context. No map-provider secret enters the APK.
router.post('/road/context', authMiddleware, async (req: AuthRequest, res) => {
  const latitude = Number(req.body?.latitude);
  const longitude = Number(req.body?.longitude);
  const previousLatitude = req.body?.previousLatitude == null ? undefined : Number(req.body.previousLatitude);
  const previousLongitude = req.body?.previousLongitude == null ? undefined : Number(req.body.previousLongitude);
  const accuracyM = req.body?.accuracyM == null ? undefined : Number(req.body.accuracyM);

  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) ||
      latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    return res.status(400).json({
      error: { code: 'invalid_location', message: 'Valid latitude and longitude are required' }
    });
  }

  try {
    const context = await getRoadContext({
      latitude,
      longitude,
      previousLatitude: typeof previousLatitude === 'number' && Number.isFinite(previousLatitude) ? previousLatitude : undefined,
      previousLongitude: typeof previousLongitude === 'number' && Number.isFinite(previousLongitude) ? previousLongitude : undefined,
      accuracyM: typeof accuracyM === 'number' && Number.isFinite(accuracyM) ? accuracyM : undefined,
    });
    // Tie speed-limit evidence to the authenticated user and exact matched
    // road. The Android app carries this proof with speeding events so the
    // scoring engine never uses the *last road's* limit for an earlier event.
    const proof = context.speedLimitTrusted && context.speedLimitKmh != null &&
      context.placeId && req.userId
      ? issueRoadLimitProof({
          u: req.userId,
          w: context.placeId,
          l: context.speedLimitKmh,
          t: Date.now(),
        })
      : null;
    if (context.speedLimitTrusted && !proof) {
      return res.json({
        ...context, speedLimitTrusted: false, source: 'VALHALLA_OSM',
        roadProof: null,
      });
    }
    res.json({ ...context, roadProof: proof });
  } catch {
    res.status(502).json({
      error: { code: 'road_context_unavailable', message: 'Road context is temporarily unavailable' }
    });
  }
});

router.post('/sync/ack', authMiddleware, async (req: AuthRequest, res) => {
  res.json({ success: true });
});

// 3. Live Dashboard (Internal/Partner)
router.get('/live/dashboard', authMiddleware, async (req: AuthRequest, res) => {
  try {
    // Never expose other drivers' real-time locations to a standard account.
    const trips = getLiveTrips().filter(trip => trip.userId === req.userId);
    res.json(trips);
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});

export default router;
