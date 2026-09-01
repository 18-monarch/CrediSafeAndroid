import axios from 'axios';
import { WebSocket } from 'ws';
import { v4 as uuidv4 } from 'uuid';

const BASE_URL = 'http://localhost:3000/v1';
const WS_URL = 'ws://localhost:3000/v1/live/trips/';
const deviceId = uuidv4();
let token = '';

async function runRealtimeTest() {
  try {
    console.log('--- Phase 1: Authentication ---');
    const authRes = await axios.post(`${BASE_URL}/auth/session`, { deviceId });
    token = authRes.data.accessToken;
    console.log('Auth Success:', token.substring(0, 20) + '...');

    const authHeader = { headers: { Authorization: `Bearer ${token}` } };

    console.log('\n--- Phase 2: Create Trip ---');
    const tripId = uuidv4();
    await axios.post(`${BASE_URL}/trips`, {
      tripId,
      startedAt: Date.now(),
      distanceM: 0,
      durationMs: 0,
      safetyScore: 100,
      telemetryQuality: 1.0,
      engineVersion: '2.7'
    }, authHeader);
    console.log('Trip Created:', tripId);

    console.log('\n--- Phase 3: Connect WebSocket ---');
    const ws = new WebSocket(`${WS_URL}${tripId}`, {
      headers: { Authorization: `Bearer ${token}` }
    });

    ws.on('open', () => {
      console.log('WebSocket Connected');

      console.log('\n--- Phase 4: Send Live Frames ---');
      for (let i = 0; i < 5; i++) {
        const frame = {
          tripId,
          sequenceNumber: i,
          timestampMs: Date.now(),
          latitude: 12.9716 + (i * 0.0001),
          longitude: 77.5946 + (i * 0.0001),
          speedKmh: 40 + (i * 2),
          bearing: 90.0,
          gpsAccuracy: 3.0,
          gpsQuality: 1.0,
          longAcc: 0.1,
          latAcc: 0.0,
          verticalAcc: 9.8,
          jerkLong: 0.0,
          jerkLat: 0.0,
          safetyEstimate: 98,
          eventCount: 0,
          telemetryQuality: 1.0,
          sensorHz: 50.0,
          jitterMs: 1.2
        };
        ws.send(JSON.stringify(frame));
        console.log(`Sent frame ${i}`);
      }
    });

    ws.on('error', (err) => console.error('WS Error:', err));

    // Wait a bit to check dashboard
    setTimeout(async () => {
      console.log('\n--- Phase 5: Verify Dashboard ---');
      const dashRes = await axios.get(`${BASE_URL}/live/dashboard`, authHeader);
      console.log('Dashboard Data:', JSON.stringify(dashRes.data, null, 2));

      if (dashRes.data.length > 0 && dashRes.data[0].tripId === tripId) {
        console.log('GENUINE REAL-TIME VERIFIED');
      } else {
        console.error('Real-time data missing from dashboard');
        process.exit(1);
      }

      ws.close();
      console.log('\n--- REAL-TIME TEST SUCCESS ---');
      process.exit(0);
    }, 3000);

  } catch (err: any) {
    console.error('\n--- REAL-TIME TEST FAILED ---');
    console.error(err.message);
    process.exit(1);
  }
}

runRealtimeTest();
