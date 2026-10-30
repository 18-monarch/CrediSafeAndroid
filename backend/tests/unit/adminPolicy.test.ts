import assert from 'node:assert/strict';
import test from 'node:test';
import { hasAdminRolePermission, isSupportedAdminConfig, sanitizeAdminDiagnostic } from '../../src/api/internalAdmin.js';

test('support role can resolve account/trip incidents but cannot edit global config or audit', () => {
  assert.equal(hasAdminRolePermission('SUPPORT','users:read'), true);
  assert.equal(hasAdminRolePermission('SUPPORT','users:write'), true);
  assert.equal(hasAdminRolePermission('SUPPORT','trips:read'), true);
  assert.equal(hasAdminRolePermission('SUPPORT','config:write'), false);
  assert.equal(hasAdminRolePermission('SUPPORT','team:manage'), false);
  assert.equal(hasAdminRolePermission('SUPPORT','audit:read'), false);
});

test('analyst and auditor roles remain read-only and separated by responsibility', () => {
  assert.equal(hasAdminRolePermission('ANALYST','xp:read'), true);
  assert.equal(hasAdminRolePermission('ANALYST','users:read'), false);
  assert.equal(hasAdminRolePermission('ANALYST','users:write'), false);
  assert.equal(hasAdminRolePermission('AUDITOR','audit:read'), true);
  assert.equal(hasAdminRolePermission('AUDITOR','config:write'), false);
  assert.equal(hasAdminRolePermission('AUDITOR','team:manage'), false);
});

test('remote configuration accepts only explicit typed settings', () => {
  assert.equal(isSupportedAdminConfig('maintenance_mode',{enabled:true,message:'Maintenance'}),true);
  assert.equal(isSupportedAdminConfig('maintenance_mode',{enabled:true,arbitrarySql:'DROP TABLE'}),false);
  assert.equal(isSupportedAdminConfig('minimum_app_version',{versionName:'2.7.0-beta.4.2',versionCode:30,force:false}),true);
  assert.equal(isSupportedAdminConfig('minimum_app_version',{versionName:'latest',versionCode:30,force:false}),false);
  assert.equal(isSupportedAdminConfig('feature_flags',{rules_engine_v2:true}),true);
  assert.equal(isSupportedAdminConfig('feature_flags',{unknown_flag:true}),false);
  assert.equal(isSupportedAdminConfig('pilot_controls',{automatic_updates_enabled:true}),true);
  assert.equal(isSupportedAdminConfig('arbitrary_key',{enabled:true}),false);
});

test('trip diagnostics redact emails, UUIDs and token-like strings', () => {
  const result = sanitizeAdminDiagnostic(
    'Sync failed for driver@example.com user 123e4567-e89b-12d3-a456-426614174000 token eyJabcdefghijklmnopqrstuvwx',
  );
  assert.equal(result?.includes('driver@example.com'),false);
  assert.equal(result?.includes('123e4567-e89b-12d3-a456-426614174000'),false);
  assert.equal(result?.includes('eyJabcdefghijklmnopqrstuvwx'),false);
  assert.match(result ?? '',/\[email\]/);
  assert.match(result ?? '',/\[id\]/);
  assert.match(result ?? '',/\[secret\]/);
});
