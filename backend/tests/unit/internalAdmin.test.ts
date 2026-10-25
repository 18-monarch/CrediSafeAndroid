import assert from 'node:assert/strict';
import test from 'node:test';
import { permittedInternalSecret } from '../../src/api/internalAdmin.js';

const secret = 'shared-team-admin-secret-must-have-32-characters';
test('bridge denies requests when unconfigured or too weak',()=>{
  assert.equal(permittedInternalSecret(secret,undefined),false);
  assert.equal(permittedInternalSecret(secret,'weak'),false);
  assert.equal(permittedInternalSecret(undefined,secret),false);
});
test('bridge accepts only the configured constant-time-matched value',()=>{
  assert.equal(permittedInternalSecret(secret,secret),true);
  assert.equal(permittedInternalSecret(secret+'a',secret),false);
  assert.equal(permittedInternalSecret('x'+secret.slice(1),secret),false);
});
