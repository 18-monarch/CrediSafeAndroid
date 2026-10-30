import assert from 'node:assert/strict';
import test from 'node:test';
import { accountSuspensionMessage, isAccountSuspended } from '../../src/auth/accountStatus.js';

test('only an explicitly suspended account is blocked', () => {
  assert.equal(isAccountSuspended('SUSPENDED'), true);
  assert.equal(isAccountSuspended('ACTIVE'), false);
  assert.equal(isAccountSuspended(undefined), false);
  assert.equal(isAccountSuspended(null), false);
});

test('suspension message does not disclose internal reason or account details', () => {
  assert.equal(
    accountSuspensionMessage('SUSPENDED'),
    'This account is temporarily suspended. Contact CrediSafe support.',
  );
  assert.equal(accountSuspensionMessage('ACTIVE'), null);
});
