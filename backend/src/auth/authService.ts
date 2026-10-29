import 'dotenv/config';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { validate as isUuid } from 'uuid';
import db from '../db/knex.js';
import { classifyAuthUniqueViolation } from './authDatabaseErrors.js';

const TOKEN_TTL_SECONDS = 12 * 60 * 60;
const REFRESH_TTL_SECONDS = 30 * 24 * 60 * 60;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export class AuthError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

function signingSecret(): string {
  const secret = process.env.JWT_SECRET;
  if (!secret || secret.length < 32) {
    throw new Error('JWT_SECRET must contain at least 32 characters.');
  }
  return secret;
}

function hashRefreshToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

function makeRefreshToken(): string {
  return randomBytes(32).toString('base64url');
}

async function issueSession(userId: string, deviceId: string) {
  const refreshToken = makeRefreshToken();
  await db('auth_refresh_sessions').insert({
    id: randomUUID(),
    user_id: userId,
    device_id: deviceId,
    token_hash: hashRefreshToken(refreshToken),
    expires_at: new Date(Date.now() + REFRESH_TTL_SECONDS * 1000),
  });
  return { ...issueToken(userId), refreshToken, refreshExpiresIn: REFRESH_TTL_SECONDS };
}

/**
 * Refresh tokens are opaque, device-bound and rotated exactly once.
 * The database stores only SHA-256 digests, never bearer secrets.
 */
export async function refreshSession(deviceId: string, refreshToken: string) {
  if (!isUuid(deviceId) || typeof refreshToken !== 'string' ||
      !/^[A-Za-z0-9_-]{40,128}$/.test(refreshToken)) {
    throw new AuthError(401, 'session_expired', 'Please sign in again.');
  }

  return db.transaction(async trx => {
    const session = await trx('auth_refresh_sessions')
      .where({ device_id: deviceId, token_hash: hashRefreshToken(refreshToken) })
      .forUpdate()
      .first();
    if (!session || session.revoked_at || new Date(session.expires_at).getTime() <= Date.now()) {
      throw new AuthError(401, 'session_expired', 'Please sign in again.');
    }

    const nextToken = makeRefreshToken();
    await trx('auth_refresh_sessions').where({ id: session.id }).update({
      token_hash: hashRefreshToken(nextToken),
      expires_at: new Date(Date.now() + REFRESH_TTL_SECONDS * 1000),
      last_used_at: new Date(),
    });
    return {
      ...issueToken(session.user_id),
      refreshToken: nextToken,
      refreshExpiresIn: REFRESH_TTL_SECONDS,
    };
  });
}

export async function revokeSession(deviceId: string, refreshToken: string) {
  if (!isUuid(deviceId) || typeof refreshToken !== 'string') return;
  await db('auth_refresh_sessions').where({
    device_id: deviceId,
    token_hash: hashRefreshToken(refreshToken),
  }).update({ revoked_at: new Date() });
}

function cleanEmail(value?: string): string | null {
  const email = value?.trim().toLowerCase() || '';
  if (!email) return null;
  if (email.length > 254 || !EMAIL_PATTERN.test(email)) {
    throw new AuthError(400, 'invalid_email', 'Enter a valid email address.');
  }
  return email;
}

function requirePassword(value?: string): string {
  const password = value || '';
  if (password.length < 8 || password.length > 72) {
    throw new AuthError(400, 'invalid_password', 'Password must be 8 to 72 characters.');
  }
  return password;
}

function issueToken(userId: string) {
  const accessToken = jwt.sign(
    { userId },
    signingSecret(),
    {
      algorithm: 'HS256',
      subject: userId,
      issuer: 'credisafe-api',
      audience: 'credisafe-android',
      expiresIn: TOKEN_TTL_SECONDS,
    },
  );
  return { accessToken, expiresIn: TOKEN_TTL_SECONDS, userId };
}

export async function createSession(deviceId: string, rawEmail?: string, rawPassword?: string, rawName?: string) {
  if (!isUuid(deviceId)) {
    throw new AuthError(400, 'invalid_device', 'A valid device identifier is required.');
  }
  const email = cleanEmail(rawEmail);
  const name = rawName?.trim().slice(0, 80) || null;

  if (!email) {
    let guest = await db('users').where({ id: deviceId }).first();
    if (!guest) {
      guest = {
        id: deviceId,
        name: `User ${deviceId.substring(0, 8)}`,
        email: `${deviceId}@credisafe.local`,
        account_type: 'guest',
        total_xp: 0,
        total_points: 0,
      };
      await db('users').insert(guest);
    }
    return issueSession(guest.id, deviceId);
  }

undefined}

export function verifyToken(token: string) {
  try {
    return jwt.verify(token, signingSecret(), {
      algorithms: ['HS256'],
      issuer: 'credisafe-api',
      audience: 'credisafe-android',
    }) as { userId: string; sub: string };
  } catch {
    return null;
  }
}
