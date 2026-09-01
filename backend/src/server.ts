import express from 'express';
import cors from 'cors';
import compression from 'compression';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import dotenv from 'dotenv';
import { createServer } from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import db from './db/knex.js';
import v1Router from './api/v1.js';
import { verifyToken } from './auth/authService.js';
import { updateLiveTrip, endLiveTrip } from './sync/liveStore.js';

dotenv.config();

const app = express();
const port = process.env.PORT || 3000;
const server = createServer(app);

app.disable('x-powered-by');
app.use(helmet());
const allowedOrigins = (process.env.CORS_ORIGINS || '')
  .split(',')
  .map(value => value.trim())
  .filter(Boolean);
app.use(cors({
  origin: allowedOrigins.length > 0 ? allowedOrigins : false,
  methods: ['GET', 'POST'],
}));
app.use(compression());
app.use(express.json({ limit: '3mb' }));
app.use('/v1/auth', rateLimit({ windowMs: 15 * 60 * 1000, limit: 20, standardHeaders: 'draft-7', legacyHeaders: false }));
app.use('/v1/road', rateLimit({ windowMs: 60 * 1000, limit: 120, standardHeaders: 'draft-7', legacyHeaders: false }));

app.get('/health', async (req, res) => {
  try {
    await db.raw('SELECT 1');
    res.json({
      status: 'ok',
      database: 'ok',
      version: '2.7.0'
    });
  } catch (err) {
    res.status(503).json({
      status: 'ok',
      database: 'down',
      version: '2.7.0'
    });
  }
});

app.use('/v1', v1Router);

// WebSocket Server
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (request, socket, head) => {
  const url = new URL(request.url || '', `http://${request.headers.host}`);
  if (url.pathname.startsWith('/v1/live/trips/')) {
    const tripId = url.pathname.split('/').pop();
    const authHeader = request.headers.authorization;
    const token = authHeader?.startsWith('Bearer ') ? authHeader.split(' ')[1] : null;

    if (!token) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    const decoded = verifyToken(token);
    if (!decoded || !tripId) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit('connection', ws, request, tripId, decoded.userId);
    });
  } else {
    socket.destroy();
  }
});

wss.on('connection', (ws: WebSocket, _request: any, tripId: string, userId: string) => {
  console.log(`Live stream connected: Trip ${tripId} by User ${userId}`);

  ws.on('message', (data) => {
    try {
      const frame = JSON.parse(data.toString());
      updateLiveTrip(tripId, userId, frame);
    } catch (err) {
      console.error('Invalid live frame', err);
    }
  });

  ws.on('close', () => {
    console.log(`Live stream closed: Trip ${tripId}`);
    // We don't immediately end the trip in store, let it timeout or wait for completion API
  });
});

server.listen(port, () => {
  console.log(`CrediSafe Backend listening at http://localhost:${port}`);
});
