import axios from 'axios';

export interface RoadContextResult {
  zoneType: 'URBAN' | 'RESIDENTIAL' | 'ARTERIAL' | 'HIGHWAY' | 'EXPRESSWAY' | 'SERVICE_ROAD' | 'UNKNOWN';
  roadName: string | null;
  placeId: string | null;
  jurisdiction: string | null;
  speedLimitKmh: number | null;
  speedLimitTrusted: boolean;
  confidence: number;
  source: 'VALHALLA_OSM_SPEED_LIMIT' | 'VALHALLA_OSM' | 'NONE';
  snappedLatitude: number | null;
  snappedLongitude: number | null;
  providerAvailable: boolean;
  roadMatched: boolean;
}

export interface RoadContextQuery {
  latitude: number;
  longitude: number;
  previousLatitude?: number;
  previousLongitude?: number;
  accuracyM?: number;
}

type CacheEntry = { value: RoadContextResult; expiresAt: number };
const cache = new Map<string, CacheEntry>();
const CACHE_TTL_MS = 20_000;
const MAX_CACHE_ENTRIES = 2_000;

const providerUnavailable = (): RoadContextResult => ({
  zoneType: 'UNKNOWN', roadName: null, placeId: null, jurisdiction: null,
  speedLimitKmh: null, speedLimitTrusted: false, confidence: 0, source: 'NONE',
  snappedLatitude: null, snappedLongitude: null, providerAvailable: false, roadMatched: false,
});

const noRoadMatch = (): RoadContextResult => ({ ...providerUnavailable(), providerAvailable: true });

export function boundedNumber(value: unknown, min: number, max: number): number | null {
  const number = Number(value);
  return Number.isFinite(number) && number >= min && number <= max ? number : null;
}

export function zoneFor(edge: any): RoadContextResult['zoneType'] {
  const roadClass = String(edge?.road_class || '').toLowerCase();
  const use = String(edge?.use || '').toLowerCase();
  if (roadClass === 'motorway' || use === 'motorway') return 'EXPRESSWAY';
  if (roadClass === 'trunk') return 'HIGHWAY';
  if (roadClass === 'primary' || roadClass === 'secondary') return 'ARTERIAL';
  if (roadClass === 'residential' || roadClass === 'unclassified') return 'RESIDENTIAL';
  if (use === 'service_road' || use === 'driveway' || roadClass === 'service') return 'SERVICE_ROAD';
  if (roadClass === 'tertiary') return 'URBAN';
  return 'UNKNOWN';
}

function firstRoadName(edge: any): string | null {
  const first = Array.isArray(edge?.names) ? edge.names[0] : null;
  if (typeof first === 'string' && first.trim()) return first.trim();
  if (typeof first?.value === 'string' && first.value.trim()) return first.value.trim();
  return null;
}

function putCache(key: string, value: RoadContextResult): void {
  if (cache.size >= MAX_CACHE_ENTRIES) {
    const oldest = cache.keys().next().value as string | undefined;
    if (oldest) cache.delete(oldest);
  }
  cache.set(key, { value, expiresAt: Date.now() + CACHE_TTL_MS });
}

function getCache(key: string): RoadContextResult | null {
  const item = cache.get(key);
  if (!item) return null;
  if (Date.now() >= item.expiresAt) {
    cache.delete(key);
    return null;
  }
  return item.value;
}

/**
 * Matches the latest GPS segment to OpenStreetMap-derived Valhalla edges.
 * A single isolated point is deliberately not treated as a confirmed road.
 * Public Valhalla is suitable for a fair-use beta; production should set
 * VALHALLA_BASE_URL to a CrediSafe-controlled deployment.
 */
export async function getRoadContext(query: RoadContextQuery): Promise<RoadContextResult> {
  const { latitude, longitude, previousLatitude, previousLongitude } = query;
  if (previousLatitude == null || previousLongitude == null) return providerUnavailable();

  const cacheKey = [previousLatitude, previousLongitude, latitude, longitude]
    .map(value => value.toFixed(4)).join(',');
  const cached = getCache(cacheKey);
  if (cached) return cached;

  const baseUrl = (process.env.VALHALLA_BASE_URL || 'https://valhalla1.openstreetmap.de')
    .replace(/\/$/, '');
  const clientId = process.env.VALHALLA_CLIENT_ID || 'CrediSafe-Open-Mobility/2.7';
  const accuracy = boundedNumber(query.accuracyM, 3, 100) ?? 25;

  try {
    const response = await axios.post(
      `${baseUrl}/trace_attributes`,
      {
        shape: [
          { lat: previousLatitude, lon: previousLongitude, type: 'break' },
          { lat: latitude, lon: longitude, type: 'break' },
        ],
        costing: 'auto',
        shape_match: 'map_snap',
        units: 'kilometers',
        trace_options: {
          gps_accuracy: accuracy,
          search_radius: Math.min(100, Math.max(25, accuracy * 2)),
          breakage_distance: 2000,
        },
        filters: {
          action: 'include',
          attributes: [
            'edge.names', 'edge.road_class', 'edge.use', 'edge.way_id',
            'edge.speed_limit', 'matched.point', 'matched.type',
            'matched.edge_index', 'matched.distance_from_trace_point',
            'admin.country_text', 'admin.state_text',
          ],
        },
      },
      {
        timeout: 8_000,
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': clientId,
          'X-Client-Id': clientId,
        },
      },
    );

    const edges = Array.isArray(response.data?.edges) ? response.data.edges : [];
    const matchedPoints = Array.isArray(response.data?.matched_points) ? response.data.matched_points : [];
    const currentMatch = matchedPoints.at(-1);
    const edgeIndex = Number.isInteger(currentMatch?.edge_index) ? currentMatch.edge_index : edges.length - 1;
    const edge = edges[edgeIndex];
    if (!edge || currentMatch?.type === 'unmatched') {
      const result = noRoadMatch();
      putCache(cacheKey, result);
      return result;
    }

    const snappedLatitude = boundedNumber(currentMatch?.lat, -90, 90);
    const snappedLongitude = boundedNumber(currentMatch?.lon, -180, 180);
    const distanceFromTrace = boundedNumber(currentMatch?.distance_from_trace_point, 0, 10_000);
    const confidence = distanceFromTrace == null ? 0.72
      : distanceFromTrace <= 10 ? 0.94
      : distanceFromTrace <= 25 ? 0.86
      : distanceFromTrace <= 50 ? 0.72 : 0.58;
    const speedLimitKmh = boundedNumber(edge.speed_limit, 5, 160);
    const speedLimitTrusted = speedLimitKmh != null && process.env.VALHALLA_SPEED_LIMITS_TRUSTED === 'true';
    const admin = Array.isArray(response.data?.admins) ? response.data.admins[0] : null;
    const jurisdiction = [admin?.state_text, admin?.country_text].filter(Boolean).join(', ') || null;
    const wayId = edge.way_id == null ? null : String(edge.way_id);

    const result: RoadContextResult = {
      zoneType: zoneFor(edge),
      roadName: firstRoadName(edge),
      placeId: wayId ? `osm-way-${wayId}` : null,
      jurisdiction,
      speedLimitKmh,
      speedLimitTrusted,
      confidence,
      source: speedLimitTrusted ? 'VALHALLA_OSM_SPEED_LIMIT' : 'VALHALLA_OSM',
      snappedLatitude,
      snappedLongitude,
      providerAvailable: true,
      roadMatched: true,
    };
    putCache(cacheKey, result);
    return result;
  } catch {
    return providerUnavailable();
  }
}
