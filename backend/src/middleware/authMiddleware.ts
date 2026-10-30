import { Request, Response, NextFunction } from 'express';
import { verifyToken } from '../auth/authService.js';
import db from '../db/knex.js';

export interface AuthRequest extends Request {
  userId?: string;
}

export async function authMiddleware(req: AuthRequest, res: Response, next: NextFunction) {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({
      error: { code: 'unauthorized', message: 'Missing or malformed Authorization header' }
    });
  }

  const token = authHeader.split(' ')[1];
  if (!token) {
    return res.status(401).json({
      error: { code: 'unauthorized', message: 'Missing token' }
    });
  }

  const decoded = verifyToken(token);
  if (!decoded) {
    return res.status(401).json({
      error: { code: 'unauthorized', message: 'Invalid or expired token' }
    });
  }

  try {
    const account = await db('users').select('account_status').where({ id: decoded.userId }).first();
    if (!account) {
      return res.status(401).json({
        error: { code: 'unauthorized', message: 'Account is no longer available' }
      });
    }
    if (account.account_status === 'SUSPENDED') {
      return res.status(403).json({
        error: { code: 'account_suspended', message: 'This account is temporarily suspended. Contact CrediSafe support.' }
      });
    }
    req.userId = decoded.userId;
    return next();
  } catch {
    return res.status(503).json({
      error: { code: 'authentication_unavailable', message: 'Unable to verify account status. Please retry.' }
    });
  }
}
