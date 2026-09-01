export const XP_RULESET_V1 = {
  version: 'XP_RULESET_V1',
  completionBaseXp: 8.0,
  maxSafetyBonusXp: 22.0,
  minSafetyScoreForBonus: 50.0,
  safetyScoreRange: 45.0,
  exposureBenchmarkMinutes: 20.0,
  minExposureFactor: 0.75,
  maxExposureFactor: 1.25,
  minQualityFactor: 0.70,
  maxQualityFactor: 1.00,
  minConfirmedTripXp: 5,
  maxConfirmedTripXp: 38,

  // Daily & Weekly Caps (UTC)
  dailyTripCompletionCapXp: 100,
  weeklyTripCompletionCapXp: 450,
  maxDailyCompletionAwards: 5,

  // Minimum Eligibility Thresholds
  minEligibleDistanceM: 500.0,
  minEligibleDurationMs: 120000,
  minGpsQualityRatio: 0.35,
  minTelemetryConfidenceRatio: 0.35,
} as const;

export interface LevelInfo {
  currentLevel: number;
  currentLevelStartingXp: number;
  nextLevelRequiredXp: number;
  xpEarnedInCurrentLevel: number;
  xpRemaining: number;
  progressPercent: number;
}

export function totalXpRequiredForLevel(level: number): number {
  if (level <= 1) return 0;
  return Math.round(100.0 * Math.pow(level - 1, 1.5));
}

export function calculateLevelFromTotalXp(totalXp: number): LevelInfo {
  const safeTotal = Math.max(0, Math.trunc(totalXp || 0));
  let level = 1;
  while (totalXpRequiredForLevel(level + 1) <= safeTotal) {
    level++;
    if (level >= 1000) break;
  }

  const currentLevelStartingXp = totalXpRequiredForLevel(level);
  const nextLevelRequiredXp = totalXpRequiredForLevel(level + 1);
  const range = Math.max(1, nextLevelRequiredXp - currentLevelStartingXp);
  const xpEarnedInCurrentLevel = safeTotal - currentLevelStartingXp;
  const xpRemaining = Math.max(0, nextLevelRequiredXp - safeTotal);
  const progressPercent = Math.min(1.0, Math.max(0.0, xpEarnedInCurrentLevel / range));

  return {
    currentLevel: level,
    currentLevelStartingXp,
    nextLevelRequiredXp,
    xpEarnedInCurrentLevel,
    xpRemaining,
    progressPercent: Math.round(progressPercent * 10000) / 10000,
  };
}

export interface XpBreakdownItem {
  code: string;
  points: number;
  reason: string;
}

export interface XpDecision {
  status: 'CONFIRMED' | 'INELIGIBLE' | 'REVIEW';
  eligible: boolean;
  tripId: string;
  rulesetVersion: string;
  confirmedXp: number;
  rewardPoints: number;
  serverSafetyScore: number;
  telemetryConfidence: number;
  reasonCodes: string[];
  breakdown: XpBreakdownItem[];
  reason: string;
}

function safeNumber(value: any, fallback = 0): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function parseAntiGamingFlags(flagsRaw: any): string[] {
  if (Array.isArray(flagsRaw)) return flagsRaw.map(String);
  try {
    const parsed = JSON.parse(flagsRaw || '[]');
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

export function reconstructServerSafetyScore(
  trip: any,
  metrics: any,
  events: any[],
): number {
  const counts = new Map<string, number>();
  for (const event of events || []) {
    const type = String(event.event_type || '');
    // Only count overspeed events when supported by trusted road speed limit context
    if (type === 'OVERSPEED_MINOR' || type === 'OVERSPEED_MAJOR') {
      const roadLimit = safeNumber(trip.road_speed_limit_kmh);
      const roadConfidence = safeNumber(trip.road_context_confidence);
      const roadMatchRatio = safeNumber(trip.road_match_ratio);
      const speedKmh = safeNumber(event.speed_kmh);

      const hasTrustedLimit = roadLimit > 0;
      const isRoadMatched = roadConfidence >= 0.70 || roadMatchRatio >= 0.50;
      const isSustainedExceed = speedKmh > roadLimit + 5.0 && safeNumber(event.confidence) >= 0.70;

      if (!hasTrustedLimit || !isRoadMatched || !isSustainedExceed) {
        // Unknown or untrusted speed limit or noisy point: DO NOT penalize overspeed
        continue;
      }
    }
    counts.set(type, (counts.get(type) || 0) + 1);
  }

  let score = safeNumber(trip.safety_score, 100);
  score -= (counts.get('OVERSPEED_MINOR') || 0) * 4.0;
  score -= (counts.get('OVERSPEED_MAJOR') || 0) * 9.0;
  score -= (counts.get('HARSH_BRAKING') || 0) * 7.0;
  score -= (counts.get('HARSH_ACCELERATION') || 0) * 5.0;
  score -= (counts.get('AGGRESSIVE_CORNERING') || 0) * 6.0;

  const gpsQuality = safeNumber(metrics?.gps_quality);
  if (gpsQuality < 0.45) score -= 5.0;
  else if (gpsQuality < 0.65) score -= 2.0;

  const telemetryQuality = safeNumber(trip.telemetry_quality);
  if (telemetryQuality < 0.45) score -= 4.0;
  else if (telemetryQuality < 0.65) score -= 2.0;

  if (safeNumber(trip.distance_m) < 150) score = Math.min(score, 85.0);

  return Math.max(0, Math.min(100, Math.round(score)));
}

export function evaluateXpDecision(
  trip: any,
  metrics: any,
  events: any[],
  dailyXpEarnedSoFar: number,
  dailyCompletionAwardsSoFar: number,
  weeklyXpEarnedSoFar: number,
): XpDecision {
  const tripId = String(trip.id || '');
  const distanceM = safeNumber(trip.distance_m);
  const durationMs = safeNumber(trip.duration_ms);
  const gpsQuality = safeNumber(metrics?.gps_quality);
  const telemetryQuality = safeNumber(trip.telemetry_quality, 1.0);
  const classification = String(trip.trip_classification || 'ELIGIBLE').toUpperCase();
  const mobilityMode = String(trip.mobility_mode || 'UNKNOWN').toUpperCase();
  const mobilityConfidence = safeNumber(trip.mobility_confidence);

  const reasonCodes: string[] = [];
  const ineligibilityReasons: string[] = [];

  const flags = parseAntiGamingFlags(trip.anti_gaming_flags_json);

  if (classification === 'NOISE' || (durationMs < 90000 && distanceM < 200)) {
    reasonCodes.push('INSUFFICIENT_DISTANCE', 'INSUFFICIENT_DURATION');
    ineligibilityReasons.push('Trip duration and distance were insufficient for a vehicle journey.');
  } else if (classification === 'INVALID' || classification === 'SUSPICIOUS') {
    reasonCodes.push('REVIEW_REQUIRED');
    ineligibilityReasons.push(trip.eligibility_reason || 'Trip classification requires review.');
  }

  if (distanceM < XP_RULESET_V1.minEligibleDistanceM) {
    if (!reasonCodes.includes('INSUFFICIENT_DISTANCE')) reasonCodes.push('INSUFFICIENT_DISTANCE');
    ineligibilityReasons.push(`Trip covered ${Math.round(distanceM)}m (minimum required: ${XP_RULESET_V1.minEligibleDistanceM}m).`);
  }

  if (durationMs < XP_RULESET_V1.minEligibleDurationMs) {
    if (!reasonCodes.includes('INSUFFICIENT_DURATION')) reasonCodes.push('INSUFFICIENT_DURATION');
    ineligibilityReasons.push(`Trip duration was ${Math.round(durationMs / 1000)}s (minimum required: 120s).`);
  }

  if (gpsQuality < XP_RULESET_V1.minGpsQualityRatio) {
    reasonCodes.push('LOW_TELEMETRY_QUALITY');
    ineligibilityReasons.push(`GPS confidence ratio was ${(gpsQuality * 100).toFixed(0)}% (minimum: 35%).`);
  }

  if (telemetryQuality < XP_RULESET_V1.minTelemetryConfidenceRatio) {
    if (!reasonCodes.includes('LOW_TELEMETRY_QUALITY')) reasonCodes.push('LOW_TELEMETRY_QUALITY');
    ineligibilityReasons.push(`Telemetry quality was ${(telemetryQuality * 100).toFixed(0)}% (minimum: 35%).`);
  }

  const nonDrivingModes: Record<string, string> = {
    WALKING: 'WALKING_TRIP',
    RUNNING: 'RUNNING_TRIP',
    BICYCLE: 'BICYCLE_TRIP',
    STILL: 'STILL_SESSION',
    POSSIBLE_RAIL_TRANSIT: 'POSSIBLE_RAIL_TRANSIT',
  };

  if (nonDrivingModes[mobilityMode] && mobilityConfidence >= 75) {
    const code = nonDrivingModes[mobilityMode];
    reasonCodes.push(code);
    ineligibilityReasons.push(`Mobility classifier identified journey as ${mobilityMode.toLowerCase().replace('_', ' ')} (${mobilityConfidence}% confidence).`);
  }

  const highRiskFlags = flags.filter(f =>
    f.startsWith('mock_location') || f.startsWith('impossible_speed') || f.startsWith('suspicious_gps_jump')
  );
  if (highRiskFlags.length > 0) {
    reasonCodes.push('ANOMALOUS_TELEMETRY');
    ineligibilityReasons.push('Telemetry integrity checks flagged anomalies (mock location, impossible speed, or GPS jumps).');
  }

  const serverSafetyScore = reconstructServerSafetyScore(trip, metrics, events);

  // If ineligible or requiring review:
  if (ineligibilityReasons.length > 0) {
    const status = highRiskFlags.length > 0 ? 'REVIEW' : 'INELIGIBLE';
    return {
      status,
      eligible: false,
      tripId,
      rulesetVersion: XP_RULESET_V1.version,
      confirmedXp: 0,
      rewardPoints: 0,
      serverSafetyScore,
      telemetryConfidence: telemetryQuality,
      reasonCodes: Array.from(new Set(reasonCodes)),
      breakdown: [
        {
          code: 'ineligible',
          points: 0,
          reason: ineligibilityReasons.join(' '),
        },
      ],
      reason: ineligibilityReasons.join(' '),
    };
  }

  // --- ELIGIBLE TRIP XP CALCULATION (XP_RULESET_V1) ---
  const completionBase = XP_RULESET_V1.completionBaseXp;

  const safeNormalized = clamp((serverSafetyScore - XP_RULESET_V1.minSafetyScoreForBonus) / XP_RULESET_V1.safetyScoreRange, 0.0, 1.0);
  const safetyBonus = XP_RULESET_V1.maxSafetyBonusXp * safeNormalized;

  const verifiedActiveMinutes = Math.max(0, durationMs / 60000.0);
  const exposureFactor = clamp(
    Math.sqrt(verifiedActiveMinutes / XP_RULESET_V1.exposureBenchmarkMinutes),
    XP_RULESET_V1.minExposureFactor,
    XP_RULESET_V1.maxExposureFactor
  );

  const qualityFactor = clamp(telemetryQuality, XP_RULESET_V1.minQualityFactor, XP_RULESET_V1.maxQualityFactor);

  const rawTripXp = Math.round((completionBase + safetyBonus) * exposureFactor * qualityFactor);
  const uncappedTripXp = clamp(rawTripXp, XP_RULESET_V1.minConfirmedTripXp, XP_RULESET_V1.maxConfirmedTripXp);

  // Calculate daily & weekly cap limits
  const dailyCapRemaining = Math.max(0, XP_RULESET_V1.dailyTripCompletionCapXp - dailyXpEarnedSoFar);
  const weeklyCapRemaining = Math.max(0, XP_RULESET_V1.weeklyTripCompletionCapXp - weeklyXpEarnedSoFar);

  let confirmedXp = uncappedTripXp;
  let capReason = '';

  if (dailyCompletionAwardsSoFar >= XP_RULESET_V1.maxDailyCompletionAwards) {
    confirmedXp = 0;
    capReason = `Daily completion award count limit reached (${XP_RULESET_V1.maxDailyCompletionAwards} awards/day).`;
    reasonCodes.push('DAILY_CAP_APPLIED');
  } else if (dailyCapRemaining < confirmedXp) {
    capReason = `Adjusted by daily XP cap (${dailyCapRemaining} XP remaining of ${XP_RULESET_V1.dailyTripCompletionCapXp} daily max).`;
    confirmedXp = dailyCapRemaining;
    reasonCodes.push('DAILY_CAP_APPLIED');
  } else if (weeklyCapRemaining < confirmedXp) {
    capReason = `Adjusted by weekly XP cap (${weeklyCapRemaining} XP remaining of ${XP_RULESET_V1.weeklyTripCompletionCapXp} weekly max).`;
    confirmedXp = weeklyCapRemaining;
    reasonCodes.push('WEEKLY_CAP_APPLIED');
  }

  reasonCodes.push('ELIGIBLE_TRIP');
  if (serverSafetyScore >= 70) reasonCodes.push('SAFE_DRIVING_BONUS');
  if (exposureFactor !== 1.0) reasonCodes.push('EXPOSURE_ADJUSTMENT');
  if (qualityFactor !== 1.0) reasonCodes.push('QUALITY_ADJUSTMENT');

  const breakdown: XpBreakdownItem[] = [
    {
      code: 'completion_base',
      points: Math.round(completionBase),
      reason: 'Eligible trip completion base.',
    },
    {
      code: 'safety_bonus',
      points: Math.round(safetyBonus),
      reason: `Safe driving bonus (${serverSafetyScore}/100 score).`,
    },
    {
      code: 'exposure_factor',
      points: 0,
      reason: `Exposure factor ${exposureFactor.toFixed(2)}x (${verifiedActiveMinutes.toFixed(1)} mins).`,
    },
    {
      code: 'quality_factor',
      points: 0,
      reason: `Telemetry quality factor ${qualityFactor.toFixed(2)}x (${(telemetryQuality * 100).toFixed(0)}%).`,
    },
  ];

  if (capReason) {
    breakdown.push({
      code: 'cap_adjustment',
      points: confirmedXp - uncappedTripXp,
      reason: capReason,
    });
  }

  breakdown.push({
    code: 'confirmed_total',
    points: confirmedXp,
    reason: `Confirmed trip XP awarded: ${confirmedXp}.`,
  });

  return {
    status: 'CONFIRMED',
    eligible: true,
    tripId,
    rulesetVersion: XP_RULESET_V1.version,
    confirmedXp,
    rewardPoints: Math.floor(confirmedXp / 2),
    serverSafetyScore,
    telemetryConfidence: telemetryQuality,
    reasonCodes: Array.from(new Set(reasonCodes)),
    breakdown,
    reason: 'Verified and confirmed by CrediSafe server.',
  };
}
