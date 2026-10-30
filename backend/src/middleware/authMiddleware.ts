import { type Request, type Response, type NextFunction } from 'express';
import { verifyToken } from '../auth/authService.js';
import db from '../db/knex.js';

export interface AuthRequest extends Request {
  userId?: string;
}

export async function authMiddleware(req: AuthRequest, res: Response, next: NextFunction) {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({
      error: { code: 'unauthorized', message: 'Missing or malformed Authorization header' },
    });
  }

  const token = authHeader.split(' ')[1];
  if (!token) {
    return res.status(401).json({ error: { code: 'unauthorized', message: 'Missing token' } });
  }

  const decoded = verifyToken(token);
  if (!decoded) {
    return res.status(401).json({ error: { code: 'unauthorized', message: 'Invalid or expired token' } });
  }

  try {
    const user = await db('users')
      .select('account_status', 'session_version')
      .where({ id: decoded.userId })
      .first();

    if (!user) {
      return res.status(401).json({
        error: { code: 'unauthorized', message: 'Account is no longer available.' },
      });
    }
    if (user.account_status === 'SUSPENDED') {
      return res.status(403).json({
        error: { code: 'account_suspended', message: 'This account is suspended. Contact CrediSafe support.' },
      });
    }

    // Old Beta JWTs without a sessionVersion are treated as version 0. When an
    // admin revokes all sessions, incrementing the account version invalidates
    // those older JWTs as well as access tokens issued by newer clients.
    const tokenVersion = Number(decoded.sessionVersion ?? 0);
    const currentVersion = Number(user.session_version ?? 0);
    if (tokenVersion !== currentVersion) {
      return res.status(401).json({
        error: { code: 'session_revoked', message: 'Your session has been revoked. Please sign in again.' },
      });
    }

    // New access JWTs are also bound to their refresh-session row, making a
    // normal per-device logout invalidate its access token immediately.
    if (decoded.sessionId) {
      const session = await db('auth_refresh_sessions')
        .select('id')
        .where({ id: decoded.sessionId, user_id: decoded.userId })
        .whereNull('revoked_at')
        .where('expires_at', '>', new Date())
        .first();
      if (!session) {
        return res.status(401).json({
          error: { code: 'session_revoked', message: 'Your session has expired or been revoked. Please sign in again.' },
        });
      }
    }

    req.userId = decoded.userId;
    next();
  } catch {
    return res.status(503).json({
      error: { code: 'auth_check_unavailable', message: 'Account and session status could not be verified. Please retry.' },
    });
  }
}
