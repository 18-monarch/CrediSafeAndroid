import axios from 'axios';
import crypto from 'crypto';
import zlib from 'zlib';
import { v4 as uuidv4 } from 'uuid';

const BASE_URL = 'http://localhost:3000/v1';
const deviceId = uuidv4();
let token = '';

async function runTests() {
  try {
    console.log('--- Phase 1: Authentication ---');
    const authRes = await axios.post(`${BASE_URL}/auth/session`, { deviceId });
    token = authRes.data.accessToken;
    console.log('Auth Success:', token.substring(0, 20) + '...');

    const authHeader = { headers: { Authorization: `Bearer ${token}` } };

    console.log('\n--- Phase 2: Create Trip ---');
    const tripId = uuidv4();
    const tripRes = await axios.post(`${BASE_URL}/trips`, {
      tripId,
      startedAt: Date.now(),
      distanceM: 1200,
      durationMs: 300000,
      safetyScore: 95,
      telemetryQuality: 0.98,
      engineVersion: '2.7'
    }, authHeader);
    console.log('Trip Created:', tripRes.data);

    console.log('\n--- Phase 3: Upload Events ---');
    const eventRes = await axios.post(`${BASE_URL}/trips/${tripId}/events`, [
      {
        timestampMs: Date.now(),
        type: 'HARSH_BRAKING',
        severity: 'MEDIUM',
        confidence: 0.9,
        speedKmh: 40.0,
        longitudinalAccel: -3.0,
        lateralAccel: 0.1,
        detail: 'Sudden deceleration detected'
      }
    ], authHeader);
    console.log('Events Uploaded:', eventRes.data);

    console.log('\n--- Phase 4: Upload Telemetry ---');
    const samples = [{ tripId: tripId, timestampMs: Date.now(), ax: 0.1, ay: 0.2, az: 9.8 }];
    const rawJson = JSON.stringify(samples);
    const compressedData = zlib.gzipSync(rawJson).toString('base64');
    const sha256 = crypto.createHash('sha256').update(rawJson).digest('hex');

    const telemetryRes = await axios.post(`${BASE_URL}/trips/${tripId}/telemetry`, {
      tripId,
      sampleCount: samples.length,
      samplingRateHz: 50.0,
      firstTimestamp: samples[0]?.timestampMs || 0,
      lastTimestamp: samples[0]?.timestampMs || 0,
      compression: 'GZIP',
      contentType: 'application/json',
      sha256,
      data: compressedData
    }, authHeader);
    console.log('Telemetry Uploaded:', telemetryRes.data);

    console.log('\n--- Phase 5: Complete Trip ---');
    const completeRes = await axios.post(`${BASE_URL}/trips/${tripId}/complete`, {}, authHeader);
    console.log('Trip Completed (Authoritative):', completeRes.data);

    console.log('\n--- Phase 6: Verification ---');
    const detailsRes = await axios.get(`${BASE_URL}/trips/${tripId}`, authHeader);
    console.log('Final Database State:', {
      status: detailsRes.data.status,
      xp: detailsRes.data.xp,
      points: detailsRes.data.reward_points,
      events: detailsRes.data.events.length,
      asset: detailsRes.data.telemetryAsset.trip_id
    });

    console.log('\n--- Phase 7: Sync Acknowledgement ---');
    const ackRes = await axios.post(`${BASE_URL}/sync/ack`, { tripIds: [tripId] }, authHeader);
    console.log('Sync Acknowledged:', ackRes.data);

    console.log('\n--- E2E TEST SUCCESS ---');
  } catch (err: any) {
    console.error('\n--- E2E TEST FAILED ---');
    if (err.response) {
      console.error('Status:', err.response.status);
      console.error('Data:', JSON.stringify(err.response.data, null, 2));
    } else {
      console.error('Error:', err.message);
    }
    process.exit(1);
  }
}

runTests();
