# CrediSafe Android v2.4 — Trip Intelligence

CrediSafe is a native Android telematics client that records real GNSS + IMU data locally, validates whether a recording is a meaningful driving trip, calculates explainable safety/XP results, and synchronizes eligible journeys with the CrediSafe backend.

## v2.4 upgrade

### Trip validity
Every recording is finalized as one of:

- `ELIGIBLE` — meaningful trip; safety/XP/rewards are calculated and it is queued for cloud sync.
- `NOISE` — accidental/tiny/stationary recording; zero XP/rewards, hidden from normal history, marked `SKIPPED`, and automatically purged after a short diagnostic retention window.
- `INVALID` — meaningful recording that fails minimum distance/duration/GPS/telemetry quality; retained for explanation but does not affect XP, rewards, streak, or sync.
- `SUSPICIOUS` — integrity/anomaly flags such as mock location or implausible GPS movement; retained for review and never rewarded.

The validity rules live in `domain/TripValidityEngine.kt` so pilot calibration does not require rewriting the telemetry service.

### Context-aware road rules
CrediSafe no longer assumes a universal 60 km/h legal limit.

`RoadContextEngine`/`RoadRuleEngine` uses road context returned by the CrediSafe backend. Overspeed events are created only when the app has a **fresh, trusted speed limit** with sufficient confidence. If a trusted limit is unavailable, CrediSafe does not invent a limit and does not penalize the driver.

Possible road zones:
`URBAN`, `RESIDENTIAL`, `ARTERIAL`, `HIGHWAY`, `EXPRESSWAY`, `SERVICE_ROAD`, `UNKNOWN`.

### Google Maps integration
The existing Maps Compose trip map is preserved and now uses a secure manifest placeholder rather than a hard-coded key.

Put this in `local.properties`:

```properties
GOOGLE_MAPS_API_KEY=YOUR_ANDROID_RESTRICTED_MAPS_KEY
CREDISAFE_API_DEBUG_URL=https://credisafeandroid.onrender.com/v1/
CREDISAFE_API_RELEASE_URL=https://credisafeandroid.onrender.com/v1/
```

Do not commit `local.properties`.

For server-side road matching, configure Render/backend environment variables:

```text
GOOGLE_MAPS_SERVER_API_KEY=...
GOOGLE_ROADS_SPEED_LIMITS_ENABLED=false
```

Enable `GOOGLE_ROADS_SPEED_LIMITS_ENABLED=true` only when the Google Maps Platform project has the required Speed Limits entitlement. Without it, CrediSafe still obtains snapped-road/address context when configured, but treats legal speed limit as unavailable.

## Data pipeline

```text
START TRIP
  -> GNSS + accelerometer + gyroscope + linear acceleration + rotation vector
  -> local SQLite
  -> road context (periodic, backend-proxied)
  -> local safety/event processing
STOP TRIP
  -> TripValidityEngine
  -> eligible? safety + XP + rewards + streak
  -> atomic local finalization
  -> WorkManager sync only for eligible trips
  -> server authoritative result
```

## Privacy/security boundaries

- Android never receives Neon/PostgreSQL credentials.
- Server-side Google Roads/Geocoding key stays on the backend.
- Android Maps SDK key should be restricted to the Android application package/signing certificate.
- Raw telemetry remains local-first and is compressed for final sync.
- No fabricated road speed limit is used for scoring.

## Build

```bash
./gradlew clean test assembleDebug
```

The project requires JDK 17 and Android SDK matching `compileSdk = 37`.

## Backend

The `backend/` folder contains the Render/Neon API. Apply migrations in order, including:

```text
backend/migrations/20260828000000_trip_intelligence_v2_4.sql
```

Then configure Render environment variables from `backend/.env.example`.

## Important pilot limitation

Road context and speed-limit data are reference inputs, not substitutes for posted road signs or local traffic law. CrediSafe must not present map-provider data as guaranteed legal truth.
