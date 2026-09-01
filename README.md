# CrediSafe Android v2.7 — Open Mobility Beta

CrediSafe is a native Android telematics beta for recording journeys, rejecting non-driving activity, estimating driving safety, and synchronizing server-authoritative XP.

## What v2.7 changes

- Replaces Google Maps Platform with MapLibre Native and the OpenFreeMap Liberty style.
- Uses OpenStreetMap-derived Valhalla map matching through the CrediSafe backend.
- Requires no Google Maps key, billing account, or Maps SDK.
- Keeps Google Play services only for fused location and Activity Recognition.
- Hashes passwords with bcrypt and verifies them on login.
- Strengthens JWT validation, rate limiting, HTTP headers, CORS, and live-dashboard access.
- Keeps overspeed penalties disabled unless a fresh, concrete speed limit is explicitly trusted.
- Keeps server safety/XP results authoritative and ledger writes idempotent.

## Architecture

```text
Android GNSS + IMU + Activity Recognition
        |
        +--> local SQLite + immediate safety preview
        +--> MapLibre + OpenFreeMap visual map
        +--> CrediSafe API
                 |
                 +--> Valhalla / OpenStreetMap road matching
                 +--> trip validation + anti-gaming checks
                 +--> server safety + XP authority
                 +--> Neon PostgreSQL
```

Map tiles never decide safety or XP. A tile outage cannot invalidate a trip. Road matching is a separate backend signal and safely degrades to unknown.

## Android identity

```text
Package: com.credisafe.mobile
Version: 2.7.0-beta.1
Version code: 25
Minimum Android: Android 8.0 / API 26
Default API: https://credisafeandroid.onrender.com/v1/
```

Reuse the same beta signing key used for v2.5/v2.6 so existing installations can update without uninstalling.

## Quick start

1. Copy `local.properties.example` to `local.properties` and keep Android Studio's `sdk.dir` line.
2. Restore the existing local `signing.properties` and `.signing/credisafe-beta.keystore` files.
3. Run:

```powershell
python .\scripts\verify-distribution.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\build-beta.ps1"
```

No map API key is required. Build output is written to `release-output/`.

## Backend setup

From `backend/`:

```bash
npm ci
npm run typecheck
```

Create `.env` from `.env.example`. Set a strong random `JWT_SECRET` of at least 32 characters and the Neon `DATABASE_URL`.

Apply migrations in filename order, including:

```text
20260829000000_mobility_intelligence_v2_6.sql
20260831000000_open_mobility_v2_7.sql
```

The public Valhalla endpoint is a fair-use beta default. Set `VALHALLA_BASE_URL` to a CrediSafe-controlled Valhalla deployment before production scale.

## Honest system limits

- Activity Recognition cannot reliably distinguish private car from bus.
- Possible rail/transit is conservative and never inferred from provider downtime alone.
- OpenStreetMap speed-limit coverage varies. Unknown or untrusted limits never create penalties.
- OpenFreeMap's public service has no SLA; production can switch to a self-hosted style/tiles without rewriting the app.
- The current score is a versioned beta model, not an insurance-certified risk score.

## Acceptance tests

Test a normal drive, accidental short trip, walk, bicycle if practical, screen-off/background journey, offline recording, map/road-provider outage, sync retry, password failure, duplicate completion, and install-over-update from the previous signed beta.

See `START_HERE_V2_7.md`, `docs/OPEN_MOBILITY_V2_7.md`, and `docs/SECURITY_V2_7.md`.
