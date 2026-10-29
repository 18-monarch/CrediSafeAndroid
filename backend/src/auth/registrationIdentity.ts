import { randomUUID } from 'node:crypto';

/**
 * User accounts must not share their primary key with a persistent installation
 * identifier. Device IDs remain useful for binding refresh tokens to a device,
 * while every newly created password account gets its own stable user ID.
 */
export function createIndependentPasswordAccountId(deviceId: string): string {
  let accountId = randomUUID();
  while (accountId === deviceId) accountId = randomUUID();
  return accountId;
}
