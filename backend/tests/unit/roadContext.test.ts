import assert from 'node:assert/strict';
import test from 'node:test';
import { boundedNumber, selectMappedSpeedLimit, zoneFor } from '../../src/services/roadContextService.js';

test('Valhalla road classes map conservatively', () => {
  assert.equal(zoneFor({ road_class: 'motorway' }), 'EXPRESSWAY');
  assert.equal(zoneFor({ road_class: 'trunk' }), 'HIGHWAY');
  assert.equal(zoneFor({ road_class: 'primary' }), 'ARTERIAL');
  assert.equal(zoneFor({ road_class: 'residential' }), 'RESIDENTIAL');
  assert.equal(zoneFor({ road_class: 'path' }), 'UNKNOWN');
});

test('invalid or implausible speed limits are rejected', () => {
  assert.equal(boundedNumber(50, 5, 160), 50);
  assert.equal(boundedNumber(0, 5, 160), null);
  assert.equal(boundedNumber(255, 5, 160), null);
  assert.equal(boundedNumber('not-a-number', 5, 160), null);
});

test('auto road limit: accepts only a tagged limit with close high-quality map match', () => {
  assert.deepEqual(
    selectMappedSpeedLimit({ speed_limit: 50, speed_type: 'tagged' }, 8, 7, true),
    { speedLimitKmh: 50, trusted: true },
  );
  assert.deepEqual(
    selectMappedSpeedLimit({ speed_limit: 60, speed_type: 'tagged' }, 8, 7, true),
    { speedLimitKmh: 60, trusted: true },
  );
});

test('inferred routing speed must NEVER become a speed limit', () => {
  assert.deepEqual(
    selectMappedSpeedLimit({ speed: 80, speed_type: 'classified' }, 4, 5, true),
    { speedLimitKmh: null, trusted: false },
  );
  assert.deepEqual(
    selectMappedSpeedLimit({ speed: 80, speed_limit: 80, speed_type: 'classified' }, 4, 5, true),
    { speedLimitKmh: 80, trusted: false },
  );
  assert.deepEqual(
    selectMappedSpeedLimit({ speed_limit: 55 }, 4, 5, true),
    { speedLimitKmh: 55, trusted: false },
  );
});

test('road-limit trust fails closed on missing GPS, distant road, provider off or implausible limit', () => {
  const e = { speed_limit: 50, speed_type: 'tagged' };
  assert.equal(selectMappedSpeedLimit(e, null, 8, true).trusted, false);
  assert.equal(selectMappedSpeedLimit(e, 30, 8, true).trusted, false);
  assert.equal(selectMappedSpeedLimit(e, 8, null, true).trusted, false);
  assert.equal(selectMappedSpeedLimit(e, 8, 35, true).trusted, false);
  assert.equal(selectMappedSpeedLimit(e, 8, 8, false).trusted, false);
  assert.deepEqual(selectMappedSpeedLimit({ ...e, speed_limit: 0 }, 8, 8, true),
    { speedLimitKmh: null, trusted: false });
  assert.deepEqual(selectMappedSpeedLimit({ ...e, speed_limit: 180 }, 8, 8, true),
    { speedLimitKmh: null, trusted: false });
});
