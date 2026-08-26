import db from '../db/knex.js';
import { endLiveTrip } from '../sync/liveStore.js';

export interface CreateTripRequest {
  tripId: string;
  userId?: string;
  vehicleId?: string;
  startedAt: number;
  endedAt?: number | null;
  distanceM: number;
  durationMs: number;
  avgSpeedKmh?: number;
  maxSpeedKmh?: number;
  gpsQuality?: number;
  sensorQuality?: number;
  safetyScore?: number | null;
  telemetryQuality: number;
  engineVersion?: string | null;
}

export async function createOrUpdateTrip(userId: string, data: CreateTripRequest) {
  const existing = await db('trips').where({ id: data.tripId }).first();

  const tripData = {
    id: data.tripId,
    user_id: userId,
    vehicle_id: data.vehicleId || null,
    started_at: new Date(data.startedAt).toISOString(),
    ended_at: data.endedAt ? new Date(data.endedAt).toISOString() : null,
    distance_m: data.distanceM,
    duration_ms: data.durationMs,
    safety_score: data.safetyScore || null,
    telemetry_quality: data.telemetryQuality,
    engine_version: data.engineVersion || null,
    status: 'COMPLETED'
  };

  if (existing) {
    if (existing.user_id !== userId) {
      throw new Error('Unauthorized: Trip ownership mismatch');
    }
    if (existing.status === 'VALIDATED') {
      return { success: true, message: 'Trip already validated' };
    }

    await db('trips').where({ id: data.tripId }).update(tripData);
    return { success: true, message: 'Trip updated' };
  }

  await db('trips').insert(tripData);
  return { success: true, message: 'Trip created' };
}

export async function uploadEvents(tripId: string, userId: string, events: any[]) {
  const trip = await db('trips').where({ id: tripId, user_id: userId }).first();
  if (!trip) throw new Error('Trip not found or unauthorized');

  const rows = events.map(e => ({
    trip_id: tripId,
    timestamp_ms: e.timestampMs,
    event_type: e.type,
    severity: e.severity,
    confidence: e.confidence,
    speed_kmh: e.speedKmh || 0,
    long_acc: e.long_acc || 0,
    lat_acc: e.lat_acc || 0,
    detail: e.detail || null
  }));

  if (rows.length > 0) {
    await db('driving_events').insert(rows).onConflict(['trip_id', 'timestamp_ms']).merge();
  }
}

export async function completeTrip(tripId: string, userId: string) {
  const trip = await db('trips').where({ id: tripId, user_id: userId }).first();
  if (!trip) throw new Error('Trip not found or unauthorized');

  const events = await db('driving_events').where({ trip_id: tripId });

  const authoritativeXp = calculateXp(trip, events);
  const authoritativePoints = Math.floor(authoritativeXp / 2);

  await db.transaction(async trx => {
    await trx('trips').where({ id: tripId }).update({
      status: 'VALIDATED',
      safety_score: trip.safety_score,
      xp: authoritativeXp,
      reward_points: authoritativePoints
    });

    // Populate trip_metrics for analytics
    await trx('trip_metrics').insert({
        trip_id: tripId,
        avg_speed_kmh: trip.avg_speed_kmh,
        max_speed_kmh: trip.max_speed_kmh,
        gps_quality: trip.gps_quality,
        sensor_quality: trip.sensor_quality
    }).onConflict('trip_id').merge();

    await trx('xp_ledger').insert({
      user_id: userId,
      trip_id: tripId,
      category: 'total',
      points: authoritativeXp,
      reason: 'Authoritative trip validation',
      engine_version: trip.engine_version,
      created_at: Date.now()
    });

    await trx('reward_ledger').insert({
      user_id: userId,
      trip_id: tripId,
      points: authoritativePoints,
      activity_type: 'trip_reward',
      created_at: Date.now()
    });

    await trx('users').where({ id: userId }).increment('total_xp', authoritativeXp);
    await trx('users').where({ id: userId }).increment('total_points', authoritativePoints);
  });

  endLiveTrip(tripId);

  return {
    success: true,
    authoritativeXp,
    authoritativePoints,
    engineVersion: trip.engine_version || '2.1'
  };
}

export async function listTrips(userId: string, limit: number = 50) {
  return db('trips')
    .where({ user_id: userId })
    .orderBy('started_at', 'desc')
    .limit(limit);
}

export async function getTripDetails(tripId: string, userId: string) {
  const trip = await db('trips').where({ id: tripId, user_id: userId }).first();
  if (!trip) return null;

  const events = await db('driving_events').where({ trip_id: tripId });
  const asset = await db('telemetry_assets').where({ trip_id: tripId }).first();

  return {
    ...trip,
    events,
    telemetryAsset: asset
  };
}

function calculateXp(trip: any, events: any[]): number {
    if (trip.distance_m < 500 || trip.duration_ms < 120000) return 0;

    let xp = 25;
    const score = trip.safety_score || 0;
    if (score >= 95) xp += 110;
    else if (score >= 90) xp += 90;
    else if (score >= 80) xp += 60;
    else if (score >= 70) xp += 40;
    else if (score >= 50) xp += 20;
    else xp += 5;

    const distanceXp = Math.min(50, Math.floor(trip.distance_m / 1000 * 2));
    xp += distanceXp;

    const overspeeds = events.filter(e => e.event_type.startsWith('OVERSPEED')).length;
    if (overspeeds === 0 && score >= 90) xp += 25;

    return Math.min(220, xp);
}
