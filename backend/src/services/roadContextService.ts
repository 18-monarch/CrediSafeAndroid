import axios from 'axios';

export interface RoadContextResult {
  zoneType: 'URBAN' | 'RESIDENTIAL' | 'ARTERIAL' | 'HIGHWAY' | 'EXPRESSWAY' | 'SERVICE_ROAD' | 'UNKNOWN';
  roadName: string | null;
  placeId: string | null;
  jurisdiction: string | null;
  speedLimitKmh: number | null;
  speedLimitTrusted: boolean;
  confidence: number;
  source: 'GOOGLE_ROADS_SPEED_LIMIT' | 'GOOGLE_ROADS_GEOCODE' | 'GOOGLE_ROADS' | 'NONE';
  snappedLatitude: number | null;
  snappedLongitude: number | null;
}

const UNKNOWN: RoadContextResult = {
  zoneType: 'UNKNOWN',
  roadName: null,
  placeId: null,
  jurisdiction: null,
  speedLimitKmh: null,
  speedLimitTrusted: false,
  confidence: 0,
  source: 'NONE',
  snappedLatitude: null,
  snappedLongitude: null,
};

function classifyRoad(routeName: string | null, hasLocality: boolean): RoadContextResult['zoneType'] {
  const value = (routeName || '').toLowerCase();
  if (/\b(expressway|freeway|motorway)\b/.test(value)) return 'EXPRESSWAY';
  if (/\b(national highway|state highway|highway)\b/.test(value) || /\b[ns]h\s*-?\s*\d+\b/i.test(routeName || '')) return 'HIGHWAY';
  if (/\bservice road\b/.test(value)) return 'SERVICE_ROAD';
  if (/\b(arterial|ring road|bypass)\b/.test(value)) return 'ARTERIAL';
  if (hasLocality) return 'URBAN';
  return 'UNKNOWN';
}

function addressComponent(result: any, type: string): string | null {
  const c = result?.address_components?.find((x: any) => Array.isArray(x.types) && x.types.includes(type));
  return c?.long_name || null;
}

/**
 * Server-side proxy for Google Roads/Geocoding.
 *
 * The Android app never receives GOOGLE_MAPS_SERVER_API_KEY. If the server key
 * is not configured, the endpoint intentionally returns UNKNOWN rather than
 * inventing road rules.
 */
export async function getRoadContext(latitude: number, longitude: number): Promise<RoadContextResult> {
  const key = process.env.GOOGLE_MAPS_SERVER_API_KEY;
  if (!key) return UNKNOWN;

  try {
    const nearest = await axios.get('https://roads.googleapis.com/v1/nearestRoads', {
      params: {
        points: `${latitude},${longitude}`,
        key,
      },
      timeout: 8000,
    });

    const snapped = nearest.data?.snappedPoints?.[0];
    if (!snapped?.placeId) return UNKNOWN;

    const placeId = snapped.placeId as string;
    const snappedLatitude = snapped.location?.latitude ?? null;
    const snappedLongitude = snapped.location?.longitude ?? null;

    let geocode: any = null;
    try {
      const geoResponse = await axios.get('https://maps.googleapis.com/maps/api/geocode/json', {
        params: { place_id: placeId, key },
        timeout: 8000,
      });
      geocode = geoResponse.data?.results?.[0] ?? null;
    } catch {
      // Nearest-road identity is still useful even if geocoding is unavailable.
    }

    const routeName = addressComponent(geocode, 'route');
    const locality =
      addressComponent(geocode, 'locality') ||
      addressComponent(geocode, 'administrative_area_level_2') ||
      addressComponent(geocode, 'sublocality');

    const state = addressComponent(geocode, 'administrative_area_level_1');
    const country = addressComponent(geocode, 'country');
    const jurisdiction = [locality, state, country].filter(Boolean).join(', ') || null;
    const zoneType = classifyRoad(routeName, Boolean(locality));

    let speedLimitKmh: number | null = null;
    let speedLimitTrusted = false;

    // Google's Roads Speed Limits endpoint has limited licensing/access.
    // It is opt-in so normal projects do not fail or create false rules.
    if (process.env.GOOGLE_ROADS_SPEED_LIMITS_ENABLED === 'true') {
      try {
        const speedResponse = await axios.get('https://roads.googleapis.com/v1/speedLimits', {
          params: { placeId, units: 'KPH', key },
          timeout: 8000,
        });
        const raw = speedResponse.data?.speedLimits?.[0]?.speedLimit;
        if (typeof raw === 'number' && Number.isFinite(raw) && raw > 0) {
          speedLimitKmh = raw;
          speedLimitTrusted = true;
        }
      } catch {
        // Never fall back to an invented legal limit.
      }
    }

    const confidence =
      speedLimitTrusted ? 0.95 :
      zoneType === 'HIGHWAY' || zoneType === 'EXPRESSWAY' ? 0.86 :
      geocode ? 0.75 :
      0.60;

    return {
      zoneType,
      roadName: routeName || geocode?.formatted_address || null,
      placeId,
      jurisdiction,
      speedLimitKmh,
      speedLimitTrusted,
      confidence,
      source: speedLimitTrusted ? 'GOOGLE_ROADS_SPEED_LIMIT' : geocode ? 'GOOGLE_ROADS_GEOCODE' : 'GOOGLE_ROADS',
      snappedLatitude,
      snappedLongitude,
    };
  } catch {
    return UNKNOWN;
  }
}
