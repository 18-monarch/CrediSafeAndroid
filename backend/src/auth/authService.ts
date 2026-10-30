import 'dotenv/config';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { validate as isUuid } from 'uuid';
import db from '../db/knex.js';
import { classifyAuthUniqueViolation } from './authDatabaseErrors.js';
import { createIndependentPasswordAccountId } from './registrationIdentity.js';
import { accountSuspensionMessage } from './accountStatus.js';

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
    this.name = 'AuthError';
  }
}

function assertAccountActive(user: { account_status?: string | null } | null | undefined) {
  const message = accountSuspensionMessage(user?.account_status);
  if (message) throw new AuthError(403, 'account_suspended', message);
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

async function issueSession(userId: string, deviceId: string) {
  // Validate JWT signing configuration before writing a persistent refresh session.
  const access = issueToken(userId);
  const refreshToken = makeRefreshToken();
  await db('auth_refresh_sessions').insert({
    id: randomUUID(),
    user_id: userId,
    device_id: deviceId,
    token_hash: hashRefreshToken(refreshToken),
    expires_at: new Date(Date.now() + REFRESH_TTL_SECONDS * 1000),
  });
  return { ...access, refreshToken, refreshExpiresIn: REFRESH_TTL_SECONDS };
}

/**
 * Refresh tokens are opaque, device-bound and rotated once.
 * Only a SHA-256 digest is stored in Postgres.
 */
export async function refreshSession(deviceId: string, refreshToken: string) {
  if (
    !isUuid(deviceId) ||
    typeof refreshToken !== 'string' ||
    !/^[A-Za-z0-9_-]{40,128}$/.test(refreshToken)
  ) {
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

    const account = await trx('users').select('account_status').where({ id: session.user_id }).first();
    if (!account) throw new AuthError(401, 'session_expired', 'Please sign in again.');
    assertAccountActive(account);

    // If signing configuration is invalid, issueToken throws and this transaction
    // rolls back without consuming the current refresh token.
    const access = issueToken(session.user_id);
    const nextToken = makeRefreshToken();
    await trx('auth_refresh_sessions').where({ id: session.id }).update({
      token_hash: hashRefreshToken(nextToken),
      expires_at: new Date(Date.now() + REFRESH_TTL_SECONDS * 1000),
      last_used_at: new Date(),
    });

    return {
      ...access,
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

function throwKnownDatabaseConflict(error: unknown): never | void {
  const conflict = classifyAuthUniqueViolation(error);
  if (conflict) throw new AuthError(conflict.status, conflict.code, conflict.message);
}

async function findGuestOrCreate(deviceId: string) {
  let guest = await db('users').where({ id: deviceId }).first();

  // Guest-mode requests must never authenticate an account that has a password.
  if (guest && (guest.account_type !== 'guest' || guest.password_hash)) {
    throw new AuthError(
      401,
      'credentials_required',
      'Sign in with the email and password for this account.',
    );
  }

  if (guest) {
    assertAccountActive(guest);
    return guest;
  }

  const candidate = {
    id: deviceId,
    name: `User ${deviceId.substring(0, 8)}`,
    email: `${deviceId}@credisafe.local`,
    account_type: 'guest',
    total_xp: 0,
    total_points: 0,
  };

  try {
    await db('users').insert(candidate);
    return candidate;
  } catch (error) {
    // A parallel guest request on the same installation may win the insert.
    // Re-read only that device row; never treat an unrelated duplicate as success.
    const existing = await db('users').where({ id: deviceId }).first();
    if (existing && existing.account_type === 'guest' && !existing.password_hash) {
      assertAccountActive(existing);
      return existing;
    }
    throwKnownDatabaseConflict(error);
    throw error;
  }
}

export async function createSession(
  deviceId: string,
  rawEmail?: string,
  rawPassword?: string,
  rawName?: string,
) {
  if (!isUuid(deviceId)) {
    throw new AuthError(400, 'invalid_device', 'A valid device identifier is required.');
  }

  const email = cleanEmail(rawEmail);
  const name = rawName?.trim().slice(0, 80) || null;

  if (!email) {
    const guest = await findGuestOrCreate(deviceId);
    return issueSession(guest.id, deviceId);
  }

  const password = requirePassword(rawPassword);
  let user = await db('users').whereRaw('LOWER(email) = ?', [email]).first();

  if (user) {
    if (user.password_hash) {
      const valid = await bcrypt.compare(password, user.password_hash);
      if (!valid) throw new AuthError(401, 'invalid_credentials', 'Incorrect email or password.');
      assertAccountActive(user);
    } else {
      assertAccountActive(user);
      // Existing pre-v2.7 accounts can only establish their first password
      // from the same installation that owns the legacy account UUID.
      if (user.id !== deviceId || !name) {
        throw new AuthError(
          409,
          'password_setup_required',
          'Use Sign Up on the original device to secure this legacy beta account.',
        );
      }

      const passwordHash = await bcrypt.hash(password, 12);
      await db('users').where({ id: user.id }).update({
        password_hash: passwordHash,
        account_type: 'password',
        name,
        updated_at: new Date(),
      });
      user = await db('users').where({ id: user.id }).first();
      if (!user) throw new Error('Account lookup failed after legacy password setup.');
    }

    return issueSession(user.id, deviceId);
  }

  // A name is required to create an account; login never silently signs up.
  if (!name) {
    throw new AuthError(401, 'invalid_credentials', 'Incorrect email or password.');
  }

  const passwordHash = await bcrypt.hash(password, 12);
  const deviceAccount = await db('users').where({ id: deviceId }).first();
  assertAccountActive(deviceAccount);

  // Preserve trip/XP history when converting an existing guest profile.
  // The conditional update prevents two concurrent sign-ups from overwriting
  // the guest profile after both have read it.
  if (deviceAccount && deviceAccount.account_type === 'guest' && !deviceAccount.password_hash) {
    try {
      const updatedRows = await db('users')
        .where({ id: deviceId, account_type: 'guest' })
        .whereNull('password_hash')
        .update({
          email,
          name,
          password_hash: passwordHash,
          account_type: 'password',
          updated_at: new Date(),
        });

      if (updatedRows > 0) {
        user = await db('users').where({ id: deviceId }).first();
      }
    } catch (error) {
      throwKnownDatabaseConflict(error);
      throw error;
    }
  }

  // The installation identifies this device/session, not the user account.
  // New password accounts therefore get independent UUIDs. This lets people
  // sign out and register another email on the same phone without overwriting
  // or locking the existing account. Legacy guest upgrades above keep their ID.
  if (!user) {
    const candidate = {
      id: createIndependentPasswordAccountId(deviceId),
      name,
      email,
      password_hash: passwordHash,
      account_type: 'password',
      total_xp: 0,
      total_points: 0,
    };

    try {
      await db('users').insert(candidate);
      user = candidate;
    } catch (error) {
      throwKnownDatabaseConflict(error);
      throw error;
    }
  }

  if (!user) throw new Error('Account creation did not produce a user record.');
  return issueSession(user.id, deviceId);
}

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
