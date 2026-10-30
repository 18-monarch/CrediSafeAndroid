import assert from 'node:assert/strict';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import test from 'node:test';
import { validConfigValue, verifyAdminRequestSignature } from '../../src/api/internalAdmin.js';

const secret = 'operations-center-test-secret-at-least-thirty-two-characters';
const userId = randomUUID();

function signed(method: string, path: string, timestamp: string, bootstrap: boolean, body: unknown) {
  const normalized = body && typeof body === 'object' ? body : {};
  const hash = createHash('sha256').update(JSON.stringify(normalized)).digest('hex');
  const payload = [method.toUpperCase(), path, timestamp, userId, bootstrap ? '1' : '0', hash].join('\n');
  return createHmac('sha256', secret).update(payload).digest('hex');
}

test('admin signature accepts a valid short-lived assertion', () => {
  const timestamp = String(Date.now());
  const body = { reason: 'unit test', active: true };
  const signature = signed('POST', '/config', timestamp, false, body);
  assert.equal(
    verifyAdminRequestSignature(secret, 'POST', '/config', timestamp, userId, false, body, signature),
    true,
  );
});

test('admin signature rejects tampered path, user, bootstrap claim and body', () => {
  const timestamp = String(Date.now());
  const body = { reason: 'unit test', active: true };
  const signature = signed('POST', '/config', timestamp, false, body);
  assert.equal(verifyAdminRequestSignature(secret, 'POST', '/team', timestamp, userId, false, body, signature), false);
  assert.equal(verifyAdminRequestSignature(secret, 'POST', '/config', timestamp, randomUUID(), false, body, signature), false);
  assert.equal(verifyAdminRequestSignature(secret, 'POST', '/config', timestamp, userId, true, body, signature), false);
  assert.equal(verifyAdminRequestSignature(secret, 'POST', '/config', timestamp, userId, false, { reason: 'changed' }, signature), false);
});

test('admin signature rejects expired or unconfigured assertions', () => {
  const timestamp = String(Date.now() - 120_000);
  const body = {};
  const signature = signed('GET', '/authz', timestamp, true, body);
  assert.equal(verifyAdminRequestSignature(secret, 'GET', '/authz', timestamp, userId, true, body, signature), false);
  assert.equal(verifyAdminRequestSignature(undefined, 'GET', '/authz', String(Date.now()), userId, true, {}, signature), false);
});

test('remote configuration accepts only supported typed values', () => {
  assert.equal(validConfigValue('pilot_mode', true), true);
  assert.equal(validConfigValue('pilot_mode', 'true'), false);
  assert.equal(validConfigValue('feature_flags', { new_dashboard: true, beta_flow: false }), true);
  assert.equal(validConfigValue('feature_flags', { new_dashboard: 'yes' }), false);
  assert.equal(validConfigValue('maintenance_banner', 'Planned maintenance'), true);
  assert.equal(validConfigValue('minimum_android_version', '2.7.0-beta.4.2'), true);
  assert.equal(validConfigValue('minimum_android_version', 'latest'), false);
  assert.equal(validConfigValue('xp_ruleset_version', 'XP_RULESET_V2'), false);
  assert.equal(validConfigValue('arbitrary_sql', 'DROP TABLE users'), false);
});
