import crypto from 'crypto';
import zlib from 'zlib';
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
  data: string;
}

const MAX_COMPRESSED_BYTES = 2_500_000;
const MAX_UNCOMPRESSED_BYTES = 25_000_000;
const MAX_SAMPLES = 250_000;

function invalid(message: string): never {
  throw new Error(`Invalid telemetry: ${message}`);
}

export async function processTelemetry(userId: string, req: TelemetryUploadRequest) {
  const trip = await db('trips').where({ id: req.tripId, user_id: userId }).first();
  if (!trip) throw new Error('Trip not found or unauthorized');

  if (!Number.isInteger(req.sampleCount) || req.sampleCount < 1 || req.sampleCount > MAX_SAMPLES) {
    invalid('sampleCount is outside the accepted range');
  }
  if (req.compression !== 'GZIP' || req.contentType !== 'application/json') {
    invalid('only GZIP application/json payloads are accepted');
  }
  if (!/^[a-f0-9]{64}$/i.test(req.sha256 || '')) invalid('sha256 is malformed');
  if (typeof req.data !== 'string' || req.data.length === 0) invalid('data is required');

  const compressedBuffer = Buffer.from(req.data, 'base64');
  if (compressedBuffer.length === 0 || compressedBuffer.length > MAX_COMPRESSED_BYTES) {
    invalid('compressed payload is too large');
  }

  let rawJson: string;
  try {
    rawJson = zlib.gunzipSync(compressedBuffer, {
      maxOutputLength: MAX_UNCOMPRESSED_BYTES,
    }).toString('utf-8');
  } catch {
    invalid('GZIP payload is corrupt or expands beyond the safety limit');
  }

  const actualHash = crypto.createHash('sha256').update(rawJson).digest('hex');
  const suppliedHash = req.sha256.toLowerCase();
  if (
    actualHash.length !== suppliedHash.length ||
    !crypto.timingSafeEqual(Buffer.from(actualHash), Buffer.from(suppliedHash))
  ) {
    invalid('SHA-256 mismatch');
  }

  let samples: any;
  try {
    samples = JSON.parse(rawJson);
  } catch {
    invalid('payload is not valid JSON');
  }
  if (!Array.isArray(samples) || samples.length !== req.sampleCount) {
    invalid(`sample count mismatch: expected ${req.sampleCount}`);
  }

  let previousTimestamp = -1;
  for (const sample of samples) {
    const timestamp = Number(sample?.timestampMs);
    if (!Number.isFinite(timestamp) || timestamp < previousTimestamp) {
      invalid('sample timestamps must be finite and nondecreasing');
    }
    previousTimestamp = timestamp;
  }
  const first = Number(samples[0]?.timestampMs);
  const last = Number(samples.at(-1)?.timestampMs);
  if (Math.abs(first - Number(req.firstTimestamp)) > 1_000 || Math.abs(last - Number(req.lastTimestamp)) > 1_000) {
    invalid('declared timestamp range does not match the payload');
  }

  await db('telemetry_assets').insert({
    trip_id: req.tripId,
    asset_uri: `postgres://telemetry_assets/${req.tripId}`,
    sample_count: req.sampleCount,
    file_size_bytes: compressedBuffer.length,
    checksum_sha256: actualHash,
    compressed: true,
    compression: 'GZIP',
    payload_bytes: compressedBuffer,
  }).onConflict('trip_id').merge();

  return { success: true, message: 'Telemetry verified and stored' };
}
