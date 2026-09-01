import db from '../db/knex.js';
import { endLiveTrip } from '../sync/liveStore.js';
import { validate as isUuid } from 'uuid';
import {
  XP_RULESET_V1,
  calculateLevelFromTotalXp,
  evaluateXpDecision,
  reconstructServerSafetyScore,
} from './xpEngineService.js';

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
  zoneProfileJson?: string | null;
  mobilityMode?: string;
  mobilityConfidence?: number;
  mobilityReason?: string | null;
  roadMatchRatio?: number;
}

function safeNumber(value: any, fallback = 0): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function clamp(value: any, min: number, max: number): number {
  return Math.max(min, Math.min(max, safeNumber(value)));
}

function oneOf(value: any, allowed: readonly string[], fallback: string): string {
  const normalized = String(value || '').toUpperCase();
  return allowed.includes(normalized) ? normalized : fallback;
}

function getStartOfWeekUtc(d: Date): Date {
  const date = new Date(d);
  const day = date.getUTCDay();
  const diff = date.getUTCDate() - day + (day === 0 ? -6 : 1);
  const monday = new Date(date.setUTCDate(diff));
  monday.setUTCHours(0, 0, 0, 0);
  return monday;
}

export async function createOrUpdateTrip(userId: string, data: CreateTripRequest) {
  if (!isUuid(data.tripId)) throw new Error('A valid tripId is required');
  if (!Number.isFinite(data.startedAt) || data.startedAt <= 0) throw new Error('startedAt is invalid');
  if (data.endedAt != null && (!Number.isFinite(data.endedAt) || data.endedAt < data.startedAt)) {
    throw new Error('endedAt is invalid');
  }

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
    distance_m: clamp(data.distanceM, 0, 2_000_000),
    duration_ms: Math.trunc(clamp(data.durationMs, 0, 7 * 24 * 60 * 60 * 1000)),
    safety_score: data.safetyScore ?? null,
    telemetry_quality: clamp(data.telemetryQuality, 0, 1),
    engine_version: XP_RULESET_V1.version,
    trip_classification: oneOf(data.tripClassification, ['ELIGIBLE', 'NOISE', 'INVALID', 'SUSPICIOUS'], 'SUSPICIOUS'),
    eligibility_reason: data.eligibilityReason?.slice(0, 500) || null,
    anti_gaming_flags_json: JSON.stringify((data.antiGamingFlags || []).slice(0, 100).map(flag => String(flag).slice(0, 120))),
    road_zone_type: oneOf(data.roadZoneType, ['URBAN', 'RESIDENTIAL', 'ARTERIAL', 'HIGHWAY', 'EXPRESSWAY', 'SERVICE_ROAD', 'UNKNOWN'], 'UNKNOWN'),
    road_name: data.roadName?.slice(0, 200) || null,
    road_place_id: data.roadPlaceId?.slice(0, 100) || null,
    road_speed_limit_kmh: data.roadSpeedLimitKmh == null ? null : clamp(data.roadSpeedLimitKmh, 5, 160),
    road_context_confidence: clamp(data.roadContextConfidence, 0, 1),
    road_context_source: oneOf(data.roadContextSource, ['VALHALLA_OSM_SPEED_LIMIT', 'VALHALLA_OSM', 'NONE'], 'NONE'),
    zone_profile_json: data.zoneProfileJson && data.zoneProfileJson.length <= 4096 ? data.zoneProfileJson : '{}',
    mobility_mode: oneOf(data.mobilityMode, ['UNKNOWN', 'DRIVING', 'ROAD_VEHICLE', 'WALKING', 'RUNNING', 'BICYCLE', 'STILL', 'POSSIBLE_RAIL_TRANSIT'], 'UNKNOWN'),
    mobility_confidence: Math.max(0, Math.min(100, Math.trunc(safeNumber(data.mobilityConfidence)))),
    mobility_reason: data.mobilityReason || null,
    road_match_ratio: clamp(data.roadMatchRatio, 0, 1),
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
  if (!Array.isArray(events) || events.length > 5000) throw new Error('Invalid event batch');
  const trip = await db('trips').where({ id: tripId, user_id: userId }).first();
  if (!trip) throw new Error('Trip not found or unauthorized');

  const allowedTypes = ['OVERSPEED_MINOR', 'OVERSPEED_MAJOR', 'HARSH_BRAKING', 'HARSH_ACCELERATION', 'AGGRESSIVE_CORNERING', 'GPS_ANOMALY', 'TELEMETRY_ANOMALY'];
  const allowedSeverity = ['LOW', 'MEDIUM', 'HIGH'];
  const rows = events.map(e => {
    const timestamp = Number(e.timestampMs);
    const eventType = String(e.type || '').toUpperCase();
    const severity = String(e.severity || '').toUpperCase();
    if (!Number.isFinite(timestamp) || timestamp <= 0) throw new Error('Invalid event timestamp');
    if (!allowedTypes.includes(eventType)) throw new Error('Invalid event type');
    if (!allowedSeverity.includes(severity)) throw new Error('Invalid event severity');
    return {
      trip_id: tripId,
      timestamp_ms: Math.trunc(timestamp),
      event_type: eventType,
      severity,
      confidence: clamp(e.confidence, 0, 1),
      speed_kmh: clamp(e.speedKmh, 0, 400),
      long_acc: clamp(e.longitudinalAccel ?? e.long_acc, -30, 30),
      lat_acc: clamp(e.lateralAccel ?? e.lat_acc, -30, 30),
      detail: e.detail ? String(e.detail).slice(0, 500) : null,
    };
  });

  if (rows.length > 0) {
    await db('driving_events')
      .insert(rows)
      .onConflict(['trip_id', 'timestamp_ms', 'event_type'])
      .merge();
  }
}

export async function completeTrip(tripId: string, userId: string) {
  const result = await db.transaction(async trx => {
    const trip = await trx('trips')
      .where({ id: tripId, user_id: userId })
      .forUpdate()
      .first();

    if (!trip) throw new Error('Trip not found or unauthorized');

    const user = await trx('users')
      .where({ id: userId })
      .forUpdate()
      .first();

    if (!user) throw new Error('User not found');

    const userTotalXp = safeNumber(user.total_xp);
    const userLevelInfo = calculateLevelFromTotalXp(userTotalXp);

    if (trip.status === 'VALIDATED' || trip.status === 'REJECTED') {
      const existingLedger = await trx('xp_ledger')
        .where({ user_id: userId, source_type: 'TRIP', source_id: tripId })
        .first();

      let breakdown: any[] = [];
      if (existingLedger?.decision_metadata) {
        try {
          const meta = typeof existingLedger.decision_metadata === 'string'
            ? JSON.parse(existingLedger.decision_metadata)
            : existingLedger.decision_metadata;
          breakdown = meta.breakdown || [];
        } catch {
          breakdown = [];
        }
      }

      return {
        success: true,
        status: trip.status === 'VALIDATED' ? 'CONFIRMED' : 'INELIGIBLE',
        tripId,
        rulesetVersion: trip.engine_version || XP_RULESET_V1.version,
        authoritativeXp: safeNumber(trip.xp),
        authoritativePoints: safeNumber(trip.reward_points),
        authoritativeSafetyScore: safeNumber(trip.safety_score),
        engineVersion: trip.engine_version || XP_RULESET_V1.version,
        eligible: trip.status === 'VALIDATED',
        eligibilityReason: trip.eligibility_reason || null,
        breakdown,
        alreadyFinalized: true,

        // Progression details
        totalXp: userTotalXp,
        currentLevel: userLevelInfo.currentLevel,
        currentLevelStartingXp: userLevelInfo.currentLevelStartingXp,
        nextLevelRequiredXp: userLevelInfo.nextLevelRequiredXp,
        xpEarnedInCurrentLevel: userLevelInfo.xpEarnedInCurrentLevel,
        xpRemaining: userLevelInfo.xpRemaining,
        progressPercent: userLevelInfo.progressPercent,
      };
    }

    const metrics = await trx('trip_metrics').where({ trip_id: tripId }).first();
    const events = await trx('driving_events').where({ trip_id: tripId });
    const endedAt = trip.ended_at ? new Date(trip.ended_at) : new Date();

    // Calculate UTC daily and weekly stats for cap enforcement
    const startOfTodayUtc = new Date(endedAt);
    startOfTodayUtc.setUTCHours(0, 0, 0, 0);

    const startOfWeekUtc = getStartOfWeekUtc(startOfTodayUtc);

    const dailyStats = await trx('xp_ledger')
      .where({ user_id: userId, source_type: 'TRIP' })
      .where('created_at', '>=', startOfTodayUtc.toISOString())
      .select(
        trx.raw('COALESCE(SUM(delta_xp), 0) as daily_xp'),
        trx.raw('COUNT(DISTINCT source_id) as award_count')
      )
      .first();

    const weeklyStats = await trx('xp_ledger')
      .where({ user_id: userId, source_type: 'TRIP' })
      .where('created_at', '>=', startOfWeekUtc.toISOString())
      .select(
        trx.raw('COALESCE(SUM(delta_xp), 0) as weekly_xp')
      )
      .first();

    const dailyXpSoFar = safeNumber(dailyStats?.daily_xp);
    const dailyAwardsSoFar = safeNumber(dailyStats?.award_count);
    const weeklyXpSoFar = safeNumber(weeklyStats?.weekly_xp);

    const decision = evaluateXpDecision(
      trip,
      metrics,
      events,
      dailyXpSoFar,
      dailyAwardsSoFar,
      weeklyXpSoFar,
    );

    const isEligible = decision.eligible && decision.confirmedXp >= 0;
    const finalStatus = isEligible ? 'VALIDATED' : 'REJECTED';

    await trx('trips').where({ id: tripId }).update({
      status: finalStatus,
      xp: decision.confirmedXp,
      reward_points: decision.rewardPoints,
      safety_score: decision.serverSafetyScore,
      eligibility_reason: decision.reason || null,
      engine_version: XP_RULESET_V1.version,
    });

    let newTotalXp = userTotalXp;
    let newTotalPoints = safeNumber(user.total_points);

    if (isEligible) {
      const metadataJson = JSON.stringify(decision);

      // Additive immutable ledger entry with idempotency unique constraint
      try {
        await trx('xp_ledger').insert({
          user_id: userId,
          trip_id: tripId,
          source_type: 'TRIP',
          source_id: tripId,
          delta_xp: decision.confirmedXp,
          reason_code: decision.reasonCodes.join(','),
          ruleset_version: XP_RULESET_V1.version,
          decision_metadata: metadataJson,

          // Legacy fields for backward compatibility with initial schema
          category: 'CONFIRMED_TRIP',
          points: decision.confirmedXp,
          reason: decision.reason,
          engine_version: XP_RULESET_V1.version,
          created_at: new Date(),
        });
      } catch {
        // Idempotency constraint hit: ignore duplicate ledger insertion
      }

      try {
        await trx('reward_ledger').insert({
          user_id: userId,
          trip_id: tripId,
          points: decision.rewardPoints,
          activity_type: 'trip_reward',
          created_at: new Date(),
        });
      } catch {
        // Idempotency constraint hit
      }

      newTotalXp = userTotalXp + decision.confirmedXp;
      newTotalPoints = newTotalPoints + decision.rewardPoints;
    }

    const newLevelInfo = calculateLevelFromTotalXp(newTotalXp);

    await trx('users').where({ id: userId }).update({
      total_xp: newTotalXp,
      total_points: newTotalPoints,
      current_level: newLevelInfo.currentLevel,
      updated_at: new Date(),
    });

    await trx('sync_audit').insert({
      trip_id: tripId,
      sync_status: isEligible ? 'SYNCED' : 'REJECTED',
      attempt_count: 1,
      last_error: decision.reason || null,
      synced_at: new Date(),
    });

    const dailyCapRemaining = Math.max(0, XP_RULESET_V1.dailyTripCompletionCapXp - (dailyXpSoFar + decision.confirmedXp));

    return {
      success: true,
      status: decision.status,
      tripId,
      rulesetVersion: XP_RULESET_V1.version,
      authoritativeXp: decision.confirmedXp,
      authoritativePoints: decision.rewardPoints,
      authoritativeSafetyScore: decision.serverSafetyScore,
      engineVersion: XP_RULESET_V1.version,
      eligible: decision.eligible,
      eligibilityReason: decision.reason || null,
      reasonCodes: decision.reasonCodes,
      breakdown: decision.breakdown,
      alreadyFinalized: false,

      // User Progression
      totalXp: newTotalXp,
      currentLevel: newLevelInfo.currentLevel,
      currentLevelStartingXp: newLevelInfo.currentLevelStartingXp,
      nextLevelRequiredXp: newLevelInfo.nextLevelRequiredXp,
      xpEarnedInCurrentLevel: newLevelInfo.xpEarnedInCurrentLevel,
      xpRemaining: newLevelInfo.xpRemaining,
      progressPercent: newLevelInfo.progressPercent,
      dailyCapRemaining,
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

  const user = await db('users').where({ id: userId }).first();
  const levelInfo = calculateLevelFromTotalXp(safeNumber(user?.total_xp));

  return { ...trip, metrics, events, xpLedger, telemetryAsset: asset, userLevelInfo: levelInfo };
}
