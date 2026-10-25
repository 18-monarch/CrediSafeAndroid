import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { gzipSync } from 'node:zlib';
import { issueRoadLimitProof } from '../../src/services/roadLimitProof.js';
process.env.ROAD_LIMIT_PROOF_SECRET = 'unit-test-proof-signing-key-never-used-in-production';
import {
  SAFETY_RULESET_V2, decodeTelemetryV2, corroborateSafetyV2,
  type TelemetrySampleV2,
} from '../../src/services/safetyRulesV2.js';

const start = Date.parse('2026-10-24T08:00:00.000Z');
const trip = {
  user_id: 'test-driver-id',
  started_at: new Date(start),
  ended_at: new Date(start + 9000),
  telemetry_quality: 0.95,
  road_speed_limit_kmh: 50,
  road_context_confidence: 0.92,
  road_context_source: 'VALHALLA_OSM_SPEED_LIMIT',
};
function samples(acceleration = 0, speed = 35): TelemetrySampleV2[] {
  return Array.from({ length: 90 }, (_, i) => ({
    timestampMs: start + i * 100,
    longAcc: i > 25 && i < 40 ? acceleration : 0,
    latAcc: 0,
    speedKmh: speed,
    accuracyM: 8,
    rx: 0,
    ry: 0,
    rz: 0,
  }));
}
function event(type: string, index = 34) {
  return { timestamp_ms: start + index * 100, event_type: type, confidence: .85, speed_kmh: 35 };
}
function speedEvent(type = 'OVERSPEED_MINOR', index = 34, limit = 50) {
  const issuedAt = start + index * 100 - 1000;
  const proof = issueRoadLimitProof({
    u: trip.user_id, w: 'osm-way-123456', l: limit, t: issuedAt,
  });
  return {
    ...event(type, index),
    speed_kmh: 62,
    detail: `Speed exceeded trusted limit;roadProof=${proof}`,
  };
}
describe('Safety Rules v2 proof-based scoring', () => {
  it('treats a client-provided high score as irrelevant and scores only evidence', () => {
    const out = corroborateSafetyV2({ ...trip, safety_score: 100 }, [event('HARSH_BRAKING')], samples(-4));
    assert.equal(out.version, SAFETY_RULESET_V2);
    assert.equal(out.score, 93);
    assert.equal(out.confirmedEvents, 1);
    assert.equal(out.uncorroboratedEvents, 0);
  });

  it('does not subtract braking twice even if the client sent 93', () => {
    const out = corroborateSafetyV2({ ...trip, safety_score: 93 }, [event('HARSH_BRAKING')], samples(-4));
    assert.equal(out.score, 93);
  });

  it('does not penalize events absent from raw sensor evidence', () => {
    const out = corroborateSafetyV2(trip, [event('HARSH_BRAKING')], samples(0));
    assert.equal(out.score, 100);
    assert.equal(out.confirmedEvents, 0);
    assert.equal(out.uncorroboratedEvents, 1);
  });

  it('ignores speeding with no authenticated per-event map evidence', () => {
    const eventAtSpeed = { ...event('OVERSPEED_MINOR'), speed_kmh: 62 };
    const out = corroborateSafetyV2(trip, [eventAtSpeed], samples(0, 62));
    assert.equal(out.score, 100);
  });

  it('confirms speeding with a signed per-road proof and corroborating GPS speed', () => {
    const eventAtSpeed = speedEvent();
    const out = corroborateSafetyV2(trip, [eventAtSpeed], samples(0, 62));
    assert.equal(out.score, 96);
  });

  it('road changes do not change the limit used for earlier events', () => {
    const eventAtSpeed = speedEvent('OVERSPEED_MINOR', 34, 50);
    const out = corroborateSafetyV2(
      { ...trip, road_speed_limit_kmh: 110, road_context_source: 'NONE' },
      [eventAtSpeed],
      samples(0, 62),
    );
    assert.equal(out.score, 96);
  });

  it('refuses fake proof, proof from another user or wrong limit', () => {
    const valid = speedEvent();
    assert.equal(corroborateSafetyV2(
      { ...trip, user_id: 'someone-else' }, [valid], samples(0, 62),
    ).score, 100);
    assert.equal(corroborateSafetyV2(
      trip, [{ ...valid, detail: valid.detail + 'tampered' }], samples(0, 62),
    ).score, 100);
  });

  it('rejects timeline mismatch', () => {
    assert.throws(() => corroborateSafetyV2({
      ...trip,
      started_at: new Date(start + 3600000),
    }, [], samples()), /timing/);
  });

  it('never assumes missing GPS accuracy means perfect data', () => {
    const emptyGps = samples().map(s => ({ ...s, accuracyM: null }));
    const out = corroborateSafetyV2(trip, [], emptyGps);
    assert.equal(out.gpsQuality, 0);
    assert.ok(out.telemetryQuality < 0.95);
  });

  it('reports missing motion orientation evidence separately from apparent clean driving', () => {
    const noOrientation = samples().map(sample => ({
      ...sample, rx: null, ry: null, rz: null,
    }));
    const result = corroborateSafetyV2(trip, [], noOrientation);
    assert.equal(result.score, 100);
    assert.equal(result.motionCoverage, 0);
    // The trip-completion gate must mark this unobservable journey ineligible.
    assert.ok(result.motionCoverage < 0.60);
  });

  it('rejects absent telemetry and accepts valid gzip samples', () => {
    assert.throws(() => decodeTelemetryV2({}), /upload required/);
    const saved = samples();
    const payload = gzipSync(JSON.stringify(saved));
    assert.equal(decodeTelemetryV2({ payload_bytes: payload, sample_count: 90 }).length, 90);
    assert.throws(() => decodeTelemetryV2({ payload_bytes: payload, sample_count: 89 }), /mismatched/);
  });

  it('does not double-count duplicate events in the same cooldown bucket', () => {
    const e = event('HARSH_BRAKING');
    const out = corroborateSafetyV2(trip, [e, e], samples(-4));
    assert.equal(out.score, 93);
    assert.equal(out.confirmedEvents, 1);
  });
});
