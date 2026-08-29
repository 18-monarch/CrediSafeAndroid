import db from '../db/knex.js';
import { endLiveTrip } from '../sync/liveStore.js';

export interface CreateTripRequest {
  tripId: string;
  userId?: string;
  vehicleId?: string | null;
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
  antiGamingFlags?: string[];
  engineVersion?: string | null;
  tripClassification?: string;
  eligibilityReason?: string | null;
  roadZoneType?: string;
  roadName?: string | null;
  roadPlaceId?: string | null;
  roadSpeedLimitKmh?: number | null;
  roadContextConfidence?: number;
  roadContextSource?: string;
}

export interface XpComponent {
  code: string;
  points: number;
  reason: string;
}

interface XpCalculation {
  eligible: boolean;
  totalXp: number;
  rewardPoints: number;
  components: XpComponent[];
  reason: string;
}

function safeNumber(value: any, fallback = 0): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

export async function createOrUpdateTrip(userId: string, data: CreateTripRequest) {
  if (!data.tripId) throw new Error('tripId is required');

  const existing = await db('trips').where({ id: data.tripId }).first();
  if (existing && existing.user_id !== userId) {
    throw new Error('Unauthorized: Trip ownership mismatch');
  }
  if (existing?.status === 'VALIDATED' || existing?.status === 'REJECTED') {
    return { success: true, message: 'Trip already finalized' };
  }

  let verifiedVehicleId: string | null = null;
  if (data.vehicleId) {
    const vehicle = await db('vehicles').where({ id: data.vehicleId, user_id: userId }).first();
    verifiedVehicleId = vehicle?.id || null;
  }

  const tripData: any = {
    id: data.tripId,
    user_id: userId,
    vehicle_id: verifiedVehicleId,
    started_at: new Date(data.startedAt).toISOString(),
    ended_at: data.endedAt ? new Date(data.endedAt).toISOString() : null,
    distance_m: safeNumber(data.distanceM),
    duration_ms: Math.max(0, Math.trunc(safeNumber(data.durationMs))),
    safety_score: data.safetyScore ?? null,
    telemetry_quality: safeNumber(data.telemetryQuality),
    engine_version: data.engineVersion || null,
    trip_classification: data.tripClassification || 'ELIGIBLE',
    eligibility_reason: data.eligibilityReason || null,
    anti_gaming_flags_json: JSON.stringify(data.antiGamingFlags || []),
    road_zone_type: data.roadZoneType || 'UNKNOWN',
    road_name: data.roadName || null,
    road_place_id: data.roadPlaceId || null,
    road_speed_limit_kmh: data.roadSpeedLimitKmh ?? null,
    road_context_confidence: safeNumber(data.roadContextConfidence),
    road_context_source: data.roadContextSource || 'NONE',
    status: 'COMPLETED',
  };

  await db.transaction(async trx => {
    if (existing) {
      await trx('trips').where({ id: data.tripId }).update(tripData);
    } else {
      await trx('trips').insert(tripData);
    }

    await trx('trip_metrics')
      .insert({
        trip_id: data.tripId,
        avg_speed_kmh: safeNumber(data.avgSpeedKmh),
        max_speed_kmh: safeNumber(data.maxSpeedKmh),
        gps_quality: safeNumber(data.gpsQuality),
        sensor_quality: safeNumber(data.sensorQuality),
      })
      .onConflict('trip_id')
      .merge();
  });

  return { success: true, message: existing ? 'Trip updated' : 'Trip created' };
}

export async function uploadEvents(tripId: string, userId: string, events: any[]) {
  const trip = await db('trips').where({ id: tripId, user_id: userId }).first();
  if (!trip) throw new Error('Trip not found or unauthorized');

  const rows = events.map(e => ({
    trip_id: tripId,
    timestamp_ms: e.timestampMs,
    event_type: e.type,
    severity: e.severity,
    confidence: safeNumber(e.confidence),
    speed_kmh: safeNumber(e.speedKmh),
    long_acc: safeNumber(e.longitudinalAccel ?? e.long_acc),
    lat_acc: safeNumber(e.lateralAccel ?? e.lat_acc),
    detail: e.detail || null,
  }));

  if (rows.length > 0) {
    await db('driving_events')
      .insert(rows)
      .onConflict(['trip_id', 'timestamp_ms'])
      .merge();
  }
}

async function streakContext(trx: any, userId: string, endedAt: Date) {
  const startOfToday = new Date(endedAt);
  startOfToday.setHours(0, 0, 0, 0);
  const endOfToday = new Date(startOfToday.getTime() + 24 * 60 * 60 * 1000);

  const sameDay = await trx('trips')
    .where({ user_id: userId, status: 'VALIDATED' })
    .where('ended_at', '>=', startOfToday.toISOString())
    .where('ended_at', '<', endOfToday.toISOString())
    .first();

  if (sameDay) return { firstTripOfDay: false, streakDays: 1 };

  const previous = await trx('trips')
    .where({ user_id: userId, status: 'VALIDATED' })
    .whereNotNull('ended_at')
    .where('ended_at', '<', startOfToday.toISOString())
    .orderBy('ended_at', 'desc')
    .limit(90);

  const dates = new Set(
    previous.map((t: any) => new Date(t.ended_at).toISOString().slice(0, 10))
  );

  let cursor = new Date(startOfToday.getTime() - 24 * 60 * 60 * 1000);
  let priorDays = 0;
  while (dates.has(cursor.toISOString().slice(0, 10))) {
    priorDays++;
    cursor = new Date(cursor.getTime() - 24 * 60 * 60 * 1000);
  }
  return { firstTripOfDay: true, streakDays: priorDays + 1 };
}

function calculateXp(
  trip: any,
  metrics: any,
  events: any[],
  firstTripOfDay: boolean,
  streakDays: number,
): XpCalculation {
  const distanceM = safeNumber(trip.distance_m);
  const durationMs = safeNumber(trip.duration_ms);
  const gpsQuality = safeNumber(metrics?.gps_quality);
  const telemetryQuality = safeNumber(trip.telemetry_quality);
  const classification = trip.trip_classification || 'ELIGIBLE';

  const problems: string[] = [];
  if (classification !== 'ELIGIBLE') problems.push(trip.eligibility_reason || `Trip classification is ${classification}.`);
  if (distanceM < 500) problems.push('Trip covered less than 0.5 km.');
  if (durationMs < 120000) problems.push('Trip lasted less than 2 minutes.');
  if (gpsQuality < 0.35) problems.push('GPS confidence is below 35%.');
  if (telemetryQuality < 0.35) problems.push('Telemetry confidence is below 35%.');

  let flags: string[] = [];
  try {
    flags = JSON.parse(trip.anti_gaming_flags_json || '[]');
  } catch {
    flags = [];
  }
  if (flags.length > 0) problems.push('Trip contains telemetry integrity/anomaly flags.');

  if (problems.length > 0) {
    return {
      eligible: false,
      totalXp: 0,
      rewardPoints: 0,
      components: [],
      reason: problems.join(' '),
    };
  }

  const components: XpComponent[] = [
    { code: 'completion', points: 25, reason: 'Eligible trip completed.' },
  ];

  const score = Math.max(0, Math.min(100, safeNumber(trip.safety_score)));
  const safetyPoints =
    score >= 95 ? 110 :
    score >= 90 ? 90 :
    score >= 80 ? 60 :
    score >= 70 ? 40 :
    score >= 50 ? 20 : 5;
  components.push({ code: 'safety', points: safetyPoints, reason: `Safety score ${score}/100.` });

  const distancePoints = Math.min(50, Math.floor(Math.max(0, distanceM) / 1000 * 2));
  if (distancePoints > 0) {
    components.push({ code: 'distance', points: distancePoints, reason: '2 XP per validated kilometre, capped at 50 XP.' });
  }

  const overspeeds = events.filter(e =>
    e.event_type === 'OVERSPEED_MINOR' || e.event_type === 'OVERSPEED_MAJOR'
  ).length;
  if (overspeeds === 0 && score >= 90) {
    components.push({ code: 'clean_trip', points: 25, reason: 'No trusted overspeed event and safety score >= 90.' });
  }

  if (gpsQuality >= 0.90) {
    components.push({ code: 'gps_quality', points: 15, reason: 'GPS confidence >= 90%.' });
  } else if (gpsQuality >= 0.75) {
    components.push({ code: 'gps_quality', points: 8, reason: 'GPS confidence >= 75%.' });
  }

  if (firstTripOfDay && streakDays >= 2) {
    const streakPoints = Math.min(25, (streakDays - 1) * 5);
    components.push({ code: 'streak', points: streakPoints, reason: `${streakDays}-day eligible driving streak.` });
  }

  const subtotal = components.reduce((sum, item) => sum + item.points, 0);
  const totalXp = Math.min(220, subtotal);
  if (subtotal > totalXp) {
    components.push({ code: 'cap', points: totalXp - subtotal, reason: 'Per-trip XP cap is 220.' });
  }

  return {
    eligible: true,
    totalXp,
    rewardPoints: Math.floor(totalXp / 2),
    components,
    reason: '',
  };
}

export async function completeTrip(tripId: string, userId: string) {
  const result = await db.transaction(async trx => {
    const trip = await trx('trips')
      .where({ id: tripId, user_id: userId })
      .forUpdate()
      .first();

    if (!trip) throw new Error('Trip not found or unauthorized');

    if (trip.status === 'VALIDATED' || trip.status === 'REJECTED') {
      return {
        success: true,
        authoritativeXp: safeNumber(trip.xp),
        authoritativePoints: safeNumber(trip.reward_points),
        engineVersion: trip.engine_version || '2.1',
        alreadyFinalized: true,
      };
    }

    const metrics = await trx('trip_metrics').where({ trip_id: tripId }).first();
    const events = await trx('driving_events').where({ trip_id: tripId });
    const endedAt = trip.ended_at ? new Date(trip.ended_at) : new Date();

    const streak = await streakContext(trx, userId, endedAt);
    const xp = calculateXp(trip, metrics, events, streak.firstTripOfDay, streak.streakDays);

    await trx('trips').where({ id: tripId }).update({
      status: xp.eligible ? 'VALIDATED' : 'REJECTED',
      xp: xp.totalXp,
      reward_points: xp.rewardPoints,
      eligibility_reason: xp.reason || null,
    });

    if (xp.eligible) {
      for (const component of xp.components.filter(c => c.points !== 0)) {
        await trx('xp_ledger').insert({
          user_id: userId,
          trip_id: tripId,
          category: component.code,
          points: component.points,
          reason: component.reason,
          engine_version: trip.engine_version || '2.1',
          created_at: new Date(),
        });
      }

      await trx('reward_ledger').insert({
        user_id: userId,
        trip_id: tripId,
        points: xp.rewardPoints,
        activity_type: 'trip_reward',
        created_at: new Date(),
      });

      await trx('users').where({ id: userId }).increment('total_xp', xp.totalXp);
      await trx('users').where({ id: userId }).increment('total_points', xp.rewardPoints);
    }

    await trx('sync_audit').insert({
      trip_id: tripId,
      sync_status: xp.eligible ? 'SYNCED' : 'REJECTED',
      attempt_count: 1,
      last_error: xp.reason || null,
      synced_at: new Date(),
    });

    return {
      success: true,
      authoritativeXp: xp.totalXp,
      authoritativePoints: xp.rewardPoints,
      engineVersion: trip.engine_version || '2.1',
      eligible: xp.eligible,
      eligibilityReason: xp.reason || null,
      breakdown: xp.components,
      alreadyFinalized: false,
    };
  });

  endLiveTrip(tripId);
  return result;
}

export async function listTrips(userId: string, limit: number = 50) {
  return db('trips')
    .where({ user_id: userId })
    .whereNot({ status: 'DISCARDED_NOISE' })
    .orderBy('started_at', 'desc')
    .limit(limit);
}

export async function getTripDetails(tripId: string, userId: string) {
  const trip = await db('trips').where({ id: tripId, user_id: userId }).first();
  if (!trip) return null;

  const events = await db('driving_events').where({ trip_id: tripId });
  const asset = await db('telemetry_assets').where({ trip_id: tripId }).first();
  const metrics = await db('trip_metrics').where({ trip_id: tripId }).first();
  const xpLedger = await db('xp_ledger').where({ trip_id: tripId }).orderBy('id');

  return {
    ...trip,
    metrics,
    events,
    xpLedger,
    telemetryAsset: asset,
  };
}
