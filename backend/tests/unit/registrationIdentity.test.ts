import assert from 'node:assert/strict';
import test from 'node:test';
import { validate as isUuid } from 'uuid';
import { createIndependentPasswordAccountId } from '../../src/auth/registrationIdentity.js';

test('new password accounts use a UUID distinct from the installation ID', () => {
  const deviceId = '11111111-1111-4111-8111-111111111111';
  const accountId = createIndependentPasswordAccountId(deviceId);

  assert.equal(isUuid(accountId), true);
  assert.notEqual(accountId, deviceId);
});

test('different registrations receive independent account IDs on one installation', () => {
  const deviceId = '11111111-1111-4111-8111-111111111111';
  const firstAccountId = createIndependentPasswordAccountId(deviceId);
  const secondAccountId = createIndependentPasswordAccountId(deviceId);

  assert.notEqual(firstAccountId, secondAccountId);
  assert.notEqual(firstAccountId, deviceId);
  assert.notEqual(secondAccountId, deviceId);
});
