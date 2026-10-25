import { gunzipSync } from 'node:zlib';

/**
 * Rules v2 pilot: deterministic, explainable corroboration against stored
 * telemetry. A matched sample is evidence consistency, NOT proof that a
 * compromised phone could not fabricate telemetry.
 */
export const SAFETY_RULESET_V2 = 'SAFETY_RULESET_V2_PILOT';
const MAX_DECODED_BYTES = 25_000_000;
const MIN_RECORDED_SAMPLES = 10;
const EVIDENCE_WINDOW_MS = 1500;

export interface TelemetrySampleV2 {
  timestampMs: number;
  accuracyM?: number | null;
  speedKmh?: number | null;
  longAcc?: number | null;
  latAcc?: number | null;
  rx?: number | null;
  ry?: number | null;
  rz?: number | null;
}

export interface VerifiedSafetyV2 {
  version: typeof SAFETY_RULESET_V2;
  score: number;
  gpsQuality: number;
  telemetryQuality: number;
  motionCoverage: number;
  confirmedEvents: number;
  uncorroboratedEvents: number;
  penalties: Record<string, number>;
  evidenceSampleCount: number;
}

function numeric(value: unknown): number | null {
  // Treat null/missing as unknown, not as a clean zero reading.
  if (value == null || typeof value === 'boolean') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function clamp(value: number): number {
  return Math.min(1, Math.max(0, value));
}

export function decodeTelemetryV2(asset: {
  payload_bytes?: Buffer | Uint8Array | null;
  sample_count?: number;
}): TelemetrySampleV2[] {
  if (!asset?.payload_bytes) throw new Error('Verified telemetry upload required before trip completion');
  const buffer = Buffer.from(asset.payload_bytes);
  if (!buffer.length || buffer.length > 2_500_000) {
    throw new Error('Telemetry payload is missing or too large');
  }
  let samples: unknown;
  try {
    samples = JSON.parse(gunzipSync(buffer, { maxOutputLength: MAX_DECODED_BYTES }).toString('utf8'));
  } catch {
    throw new Error('Stored telemetry cannot be decoded');
  }
  if (!Array.isArray(samples) || samples.length < MIN_RECORDED_SAMPLES ||
      samples.length > 250_000 || samples.length !== Number(asset.sample_count)) {
    throw new Error('Insufficient or mismatched recorded telemetry');
  }
  let previous = -1;
  for (const raw of samples) {
    const timestamp = numeric(raw?.timestampMs);
    if (timestamp == null || timestamp < previous || timestamp <= 0) {
      throw new Error('Nonmonotonic or invalid telemetry timestamps');
    }
    previous = timestamp;
  }
  return samples as TelemetrySampleV2[];
}

function gpsFixQuality(accuracyM: number | null): number {
  if (accuracyM == null || accuracyM < 0 || accuracyM > 1000) return 0;
  if (accuracyM <= 5) return 1;
  if (accuracyM <= 10) return .95;
  if (accuracyM <= 20) return .85;
  if (accuracyM <= 40) return .65;
  if (accuracyM <= 70) return .45;
  return .2;
}

export function corroborateSafetyV2(
  trip: {
    started_at?: string | Date | null;
    ended_at?: string | Date | null;
    road_speed_limit_kmh?: unknown;
    road_context_confidence?: unknown;
    road_context_source?: string;
    telemetry_quality?: unknown;
  },
  events: Array<{
    timestamp_ms: number;
    event_type: string;
    confidence?: unknown;
    speed_kmh?: unknown;
  }>,
  samples: TelemetrySampleV2[],
): VerifiedSafetyV2 {
  if (samples.length < MIN_RECORDED_SAMPLES) throw new Error('Not enough telemetry samples');
  const start = trip.started_at ? new Date(trip.started_at).getTime() : NaN;
  const end = trip.ended_at ? new Date(trip.ended_at).getTime() : NaN;
  const first = samples[0].timestampMs;
  const last = samples[samples.length - 1].timestampMs;
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start ||
      first < start - 30_000 || last > end + 30_000 ||
      last - first < 1000) {
    throw new Error('Telemetry timing does not match recorded trip');
  }

  const spanSeconds = Math.max(1, (last - first) / 1000);
  const savedHz = (samples.length - 1) / spanSeconds;
  const rateQuality = savedHz >= 8 ? 1 : savedHz >= 5 ? .85 : savedHz >= 2 ? .65 : .35;

  let gpsSum = 0, orientationCount = 0;
  for (const sample of samples) {
    gpsSum += gpsFixQuality(numeric(sample.accuracyM));
    if (numeric(sample.longAcc) != null && numeric(sample.latAcc) != null &&
        numeric(sample.rx) != null && numeric(sample.ry) != null &&
        numeric(sample.rz) != null) orientationCount++;
  }
  const gpsQuality = clamp(gpsSum / samples.length);
  const motionQuality = orientationCount / samples.length;
  const measuredQuality = clamp(.4 * gpsQuality + .4 * rateQuality + .2 * motionQuality);
  // Telemetry cannot claim better quality than the independent server estimate.
  const reportedQuality = numeric(trip.telemetry_quality);
  const telemetryQuality = Math.min(measuredQuality, clamp(reportedQuality ?? 0));

  const weights: Record<string, number> = {
    HARSH_BRAKING: 7,
    HARSH_ACCELERATION: 5,
    AGGRESSIVE_CORNERING: 6,
    OVERSPEED_MINOR: 4,
    OVERSPEED_MAJOR: 9,
  };
  const penalties: Record<string, number> = {};
  let confirmedEvents = 0;
  let uncorroboratedEvents = 0;
  const seen = new Set<string>();

  for (const event of events) {
    const type = event.event_type;
    const weight = weights[type];
    const ts = numeric(event.timestamp_ms);
    if (!weight || ts == null || ts < first - EVIDENCE_WINDOW_MS ||
        ts > last + EVIDENCE_WINDOW_MS || (numeric(event.confidence) ?? 0) < .65) {
      uncorroboratedEvents++;
      continue;
    }
    // Avoid counting the same manoeuvre more than once within the detector cooldown.
    const bucket = type + ':' + Math.floor(ts / 8000);
    if (seen.has(bucket)) continue;
    seen.add(bucket);

    const isSpeed = type.startsWith('OVERSPEED_');
    const trustedLimit = numeric(trip.road_speed_limit_kmh);
    const trustedRoad = trip.road_context_source === 'VALHALLA_OSM_SPEED_LIMIT' &&
      (numeric(trip.road_context_confidence) ?? 0) >= .70;
    const excess = type === 'OVERSPEED_MAJOR' ? 20 : 5;
    if (isSpeed && (!trustedRoad || trustedLimit == null || trustedLimit <= 0 ||
        (numeric(event.speed_kmh) ?? 0) < trustedLimit + excess)) {
      uncorroboratedEvents++;
      continue;
    }

    let evidencePoints = 0;
    // Timestamp-ordered samples; binary search avoids N*events full scans.
    let lo = 0, hi = samples.length;
    while (lo < hi) {
      const mid = Math.floor((lo + hi) / 2);
      if (samples[mid].timestampMs < ts - EVIDENCE_WINDOW_MS) lo = mid + 1;
      else hi = mid;
    }
    for (let i = lo; i < samples.length && samples[i].timestampMs <= ts + EVIDENCE_WINDOW_MS; i++) {
      const sample = samples[i];
      const long = numeric(sample.longAcc);
      const lat = numeric(sample.latAcc);
      const speed = numeric(sample.speedKmh);
      const qualifies =
        type === 'HARSH_BRAKING' ? long != null && long <= -2.5 && speed != null && speed >= 5 :
        type === 'HARSH_ACCELERATION' ? long != null && long >= 2.7 && speed != null && speed >= 5 :
        type === 'AGGRESSIVE_CORNERING' ? lat != null && Math.abs(lat) >= 3.5 && speed != null && speed >= 25 :
        speed != null && trustedLimit != null && speed >= trustedLimit + excess;
      if (qualifies) evidencePoints++;
      if (evidencePoints >= 2) break;
    }
    if (evidencePoints < 2) {
      uncorroboratedEvents++;
      continue;
    }
    penalties[type] = (penalties[type] ?? 0) + weight;
    confirmedEvents++;
  }

  return {
    version: SAFETY_RULESET_V2,
    score: Math.max(0, 100 - Object.values(penalties).reduce((sum, n) => sum + n, 0)),
    gpsQuality,
    telemetryQuality,
    motionCoverage: motionQuality,
    confirmedEvents,
    uncorroboratedEvents,
    penalties,
    evidenceSampleCount: samples.length,
  };
}
