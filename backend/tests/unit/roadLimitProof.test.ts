import assert from 'node:assert/strict';
import test from 'node:test';
import { issueRoadLimitProof, proofFromEventDetail, verifyRoadLimitProof } from '../../src/services/roadLimitProof.js';

const signingKey = 'test-only-not-for-use-in-production';
const driverId = 'driver-1';
const now = 1792930000000;

test('offline replay can verify backend-issued road limits by event time', () => {
  const proof = issueRoadLimitProof({ u: driverId, w: 'osm-way-9123', l: 50, t: now }, signingKey);
  assert.ok(proof);
  assert.deepEqual(verifyRoadLimitProof(proof, driverId, now + 3000, signingKey),
    { u: driverId, w: 'osm-way-9123', l: 50, t: now });
  assert.equal(proofFromEventDetail('Over limit;roadProof=' + proof), proof);
});

test('rejects expired, premature, cross-user and tampered road proof', () => {
  const proof = issueRoadLimitProof({ u: driverId, w: 'osm-way-9123', l: 50, t: now }, signingKey)!;
  assert.equal(verifyRoadLimitProof(proof, 'other-driver', now + 3000, signingKey), null);
  assert.equal(verifyRoadLimitProof(proof, driverId, now + 46_000, signingKey), null);
  assert.equal(verifyRoadLimitProof(proof, driverId, now - 6000, signingKey), null);
  assert.equal(verifyRoadLimitProof(proof + 'x', driverId, now + 3000, signingKey), null);
  assert.equal(verifyRoadLimitProof(proof, driverId, now + 3000, 'wrong-key'), null);
});

test('cannot sign missing or invalid posted limits', () => {
  assert.equal(issueRoadLimitProof({ u: driverId, w: 'osm-way-1', l: 0, t: now }, signingKey), null);
  assert.equal(issueRoadLimitProof({ u: driverId, w: '', l: 50, t: now }, signingKey), null);
  assert.equal(issueRoadLimitProof({ u: driverId, w: 'osm-way-1', l: 150, t: now }, signingKey), null);
});
