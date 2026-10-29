import assert from 'node:assert/strict';
import test from 'node:test';
import { classifyAuthUniqueViolation } from '../../src/auth/authDatabaseErrors.js';

test('maps email unique violations to a recoverable account conflict', () => {
  for (const constraint of ['users_email_key', 'idx_users_email_lower_unique']) {
    const result = classifyAuthUniqueViolation({ code: '23505', constraint });
    assert.equal(result?.status, 409);
    assert.equal(result?.code, 'account_already_exists');
    assert.match(result?.message ?? '', /signing in/i);
  }
});

test('maps device primary-key collisions without exposing SQL errors', () => {
  const result = classifyAuthUniqueViolation({ code: '23505', constraint: 'users_pkey', detail: 'sensitive row values' });
  assert.equal(result?.status, 409);
  assert.equal(result?.code, 'device_account_conflict');
  assert.equal(result?.message.includes('sensitive row values'), false);
});

test('does not reclassify unrelated database failures', () => {
  assert.equal(classifyAuthUniqueViolation({ code: '42P01', table: 'auth_refresh_sessions' }), null);
  assert.equal(classifyAuthUniqueViolation({ code: '23505', constraint: 'trips_pkey' })?.code, 'device_account_conflict');
  assert.equal(classifyAuthUniqueViolation(new Error('duplicate key')), null);
});
