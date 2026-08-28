import jwt from 'jsonwebtoken';
import db from '../db/knex.js';

const JWT_SECRET = process.env.JWT_SECRET || 'secret';

export async function createSession(deviceId: string, email?: string, password?: string, name?: string) {
  let user;

  if (email) {
    user = await db('users').where({ email }).first();
    if (!user) {
      user = {
        id: deviceId,
        name: name || email.split('@')[0],
        email: email,
        total_xp: 0,
        total_points: 0
      };
      await db('users').insert(user);
    }
  } else {
    user = await db('users').where({ id: deviceId }).first();

    if (!user) {
      user = {
        id: deviceId,
        name: `User ${deviceId.substring(0, 8)}`,
        email: `${deviceId}@credisafe.local`,
        total_xp: 0,
        total_points: 0
      };
      await db('users').insert(user);
    }
  }

  const token = jwt.sign({ userId: user.id }, JWT_SECRET, { expiresIn: '1h' });

  return {
    accessToken: token,
    expiresIn: 3600,
    userId: user.id
  };
}

export function verifyToken(token: string) {
  try {
    return jwt.verify(token, JWT_SECRET) as { userId: string };
  } catch (err) {
    return null;
  }
}
