import { Router } from 'express';
import { createSession } from '../auth/authService.js';
import { authMiddleware, AuthRequest } from '../middleware/authMiddleware.js';
import * as tripService from '../services/tripService.js';
import * as telemetryService from '../services/telemetryService.js';
import db from '../db/knex.js';

import { getLiveTrips, updateLiveTrip } from '../sync/liveStore.js';

const router = Router();

// 1. Auth
router.post('/auth/session', async (req, res) => {
  try {
    const { deviceId, email, password } = req.body;
    if (!deviceId) return res.status(400).json({ error: { code: 'invalid_request', message: 'deviceId is required' } });
    const session = await createSession(deviceId as string, email as string, password as string);
    res.json(session);
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
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

router.post('/sync/ack', authMiddleware, async (req: AuthRequest, res) => {
  res.json({ success: true });
});

// 3. Live Dashboard (Internal/Partner)
router.get('/live/dashboard', async (req, res) => {
  try {
    const trips = getLiveTrips();
    res.json(trips);
  } catch (err: any) {
    res.status(500).json({ error: { code: 'server_error', message: err.message } });
  }
});

export default router;
