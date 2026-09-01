import assert from 'node:assert/strict';
import test from 'node:test';
import { boundedNumber, zoneFor } from '../../src/services/roadContextService.js';

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
