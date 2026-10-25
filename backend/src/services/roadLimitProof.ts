import { createHmac, timingSafeEqual } from 'node:crypto';

export interface RoadLimitClaim {
  u: string; // authenticated driver ID
  w: string; // matched OpenStreetMap way id
  l: number; // numeric, tagged posted speed limit in km/h
  t: number; // map lookup timestamp in Unix milliseconds
}

const MAX_EVENT_AGE_MS = 45_000;
const FUTURE_CLOCK_SKEW_MS = 5_000;

function secret(): string | null {
  return process.env.ROAD_LIMIT_PROOF_SECRET || process.env.JWT_SECRET || null;
}

/**
 * Compact tamper-evident proof returned by the authenticated map endpoint.
 * A proof establishes what the backend map provider returned; it is not
 * independent verification of an actual roadside speed sign.
 */
export function issueRoadLimitProof(
  claim: RoadLimitClaim,
  signingKey = secret(),
): string | null {
  if (!signingKey || !claim.u || !claim.w ||
      !Number.isFinite(claim.l) || claim.l < 10 || claim.l > 140 ||
      !Number.isSafeInteger(claim.t) || claim.t <= 0) return null;
  const payload = Buffer.from(JSON.stringify(claim), 'utf8').toString('base64url');
  const signature = createHmac('sha256', signingKey).update('v1.' + payload).digest('base64url');
  return 'v1.' + payload + '.' + signature;
}

export function verifyRoadLimitProof(
  proof: string | undefined | null,
  userId: string,
  eventTimestampMs: number,
  signingKey = secret(),
): RoadLimitClaim | null {
  if (!signingKey || !proof || proof.length > 450 || !Number.isFinite(eventTimestampMs)) return null;
  const parts = proof.split('.');
  if (parts.length !== 3 || parts[0] !== 'v1' ||
      !/^[A-Za-z0-9_-]+$/.test(parts[1]) ||
      !/^[A-Za-z0-9_-]+$/.test(parts[2])) return null;
  const expected = createHmac('sha256', signingKey).update('v1.' + parts[1]).digest();
  const provided = Buffer.from(parts[2], 'base64url');
  if (expected.length !== provided.length || !timingSafeEqual(expected, provided)) return null;
  let data: unknown;
  try { data = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8')); }
  catch { return null; }
  const claim = data as Partial<RoadLimitClaim>;
  if (claim.u !== userId || !claim.w || typeof claim.w !== 'string' ||
      !Number.isFinite(claim.l) || (claim.l ?? 0) < 10 || (claim.l ?? 0) > 140 ||
      !Number.isSafeInteger(claim.t) || (claim.t ?? 0) <= 0 ||
      eventTimestampMs < (claim.t ?? 0) - FUTURE_CLOCK_SKEW_MS ||
      eventTimestampMs > (claim.t ?? 0) + MAX_EVENT_AGE_MS) return null;
  return claim as RoadLimitClaim;
}

export function proofFromEventDetail(detail: unknown): string | null {
  if (typeof detail !== 'string') return null;
  return /(?:^|;)roadProof=(v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)(?:;|$)/.exec(detail)?.[1] ?? null;
}
