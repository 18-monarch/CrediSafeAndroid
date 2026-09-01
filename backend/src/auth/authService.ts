import 'dotenv/config';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { validate as isUuid } from 'uuid';
import db from '../db/knex.js';

const TOKEN_TTL_SECONDS = 12 * 60 * 60;
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
    return issueToken(guest.id);
  }

  const password = requirePassword(rawPassword);
  const passwordHash = await bcrypt.hash(password, 12);
  let user = await db('users').whereRaw('LOWER(email) = ?', [email]).first();

  if (!user) {
    if (!name) {
      throw new AuthError(401, 'invalid_credentials', 'Incorrect email or password.');
    }

    const deviceAccount = await db('users').where({ id: deviceId }).first();
    if (deviceAccount && deviceAccount.account_type === 'guest') {
      await db('users').where({ id: deviceId }).update({
        email,
        name,
        password_hash: passwordHash,
        account_type: 'password',
        updated_at: new Date(),
      });
      user = await db('users').where({ id: deviceId }).first();
    } else if (deviceAccount) {
      throw new AuthError(409, 'device_account_conflict', 'This installation is already linked to another account.');
    } else {
      user = {
        id: deviceId,
        name,
        email,
        password_hash: passwordHash,
        account_type: 'password',
        total_xp: 0,
        total_points: 0,
      };
      await db('users').insert(user);
    }
  } else if (user.password_hash) {
    const valid = await bcrypt.compare(password, user.password_hash);
    if (!valid) throw new AuthError(401, 'invalid_credentials', 'Incorrect email or password.');
  } else {
    // Safe migration for accounts created by the pre-v2.7 beta: only the same
    // installation that owns the account UUID can establish its first hash.
    if (user.id !== deviceId || !name) {
      throw new AuthError(409, 'password_setup_required', 'Use Sign Up on the original device to secure this legacy beta account.');
    }
    await db('users').where({ id: user.id }).update({
      password_hash: passwordHash,
      account_type: 'password',
      name,
      updated_at: new Date(),
    });
  }

  return issueToken(user.id);
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
