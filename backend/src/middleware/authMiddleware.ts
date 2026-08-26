import { Request, Response, NextFunction } from 'express';
import { verifyToken } from '../auth/authService.js';

export interface AuthRequest extends Request {
  userId?: string;
}

export function authMiddleware(req: AuthRequest, res: Response, next: NextFunction) {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({
      error: {
        code: 'unauthorized',
        message: 'Missing or malformed Authorization header'
      }
    });
  }

  const token = authHeader.split(' ')[1];
  if (!token) {
    return res.status(401).json({
      error: {
        code: 'unauthorized',
        message: 'Missing token'
      }
    });
  }

  const decoded = verifyToken(token);

  if (!decoded) {
    return res.status(401).json({
      error: {
        code: 'unauthorized',
        message: 'Invalid or expired token'
      }
    });
  }

  req.userId = decoded.userId;
  next();
}
