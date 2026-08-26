import zlib from 'zlib';
import crypto from 'crypto';
import db from '../db/knex.js';

export interface TelemetryUploadRequest {
  tripId: string;
  sampleCount: number;
  samplingRateHz: number;
  firstTimestamp: number;
  lastTimestamp: number;
  compression: string;
  contentType: string;
  sha256: string;
  data: string; // Base64 encoded compressed data
}

export async function processTelemetry(userId: string, req: TelemetryUploadRequest) {
  const trip = await db('trips').where({ id: req.tripId, user_id: userId }).first();
  if (!trip) throw new Error('Trip not found or unauthorized');

  const compressedBuffer = Buffer.from(req.data, 'base64');

  let rawJson: string;
  if (req.compression === 'GZIP') {
    rawJson = zlib.gunzipSync(compressedBuffer).toString('utf-8');
  } else {
    throw new Error('Unsupported compression type');
  }

  const actualHash = crypto.createHash('sha256').update(rawJson).digest('hex');
  if (actualHash !== req.sha256) {
    throw new Error('Integrity check failed: SHA-256 mismatch');
  }

  const samples = JSON.parse(rawJson);
  if (samples.length !== req.sampleCount) {
    throw new Error(`Sample count mismatch: expected ${req.sampleCount}, got ${samples.length}`);
  }

  await db('telemetry_assets').insert({
    trip_id: req.tripId,
    asset_uri: `local://${req.tripId}.gz`,
    sample_count: req.sampleCount,
    file_size_bytes: compressedBuffer.length,
    checksum_sha256: req.sha256,
    compressed: true
  }).onConflict('trip_id').merge();

  return { success: true, message: 'Telemetry verified and stored' };
}
