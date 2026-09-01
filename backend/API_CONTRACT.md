# CrediSafe API Contract — v2.7

Base URL: `https://credisafeandroid.onrender.com/v1/`. All protected calls use `Authorization: Bearer <JWT>`.

## Health

`GET /health` returns API/database status and version `2.7.0`.

## Session

`POST /v1/auth/session`

- Guest: `{ "deviceId": "uuid" }`
- Register/secure account: `{ "deviceId": "uuid", "name": "...", "email": "...", "password": "8-72 chars" }`
- Login: `{ "deviceId": "uuid", "email": "...", "password": "..." }`

Invalid credentials return `401 invalid_credentials`. A legacy beta account that must be secured on its original device returns `409 password_setup_required`.

## Trips

- `POST /v1/trips` — idempotent create/update by client-generated `tripId`.
- `POST /v1/trips/{tripId}/events` — unique by trip, timestamp, and event type.
- `POST /v1/trips/{tripId}/telemetry` — compressed telemetry plus checksum metadata.
- `POST /v1/trips/{tripId}/complete` — transactional server finalization.
- `GET /v1/trips` and `GET /v1/trips/{tripId}` — authenticated owner-only reads.

Completion returns authoritative XP, reward points, safety score, eligibility, and engine version. Repeating completion returns the already-finalized values without duplicating ledgers.

## Road context

`POST /v1/road/context` JSON fields (POST keeps precise coordinates out of normal URL logs):

```text
latitude, longitude                     required
previousLatitude, previousLongitude     required for confirmed segment matching
accuracyM, speedKmh, bearing             optional
```

The backend sends the GPS segment to Valhalla `trace_attributes` and returns normalized road class, name, OSM way identifier, snapped point, confidence, and speed-limit evidence where available.

Example source values are `VALHALLA_OSM`, `VALHALLA_OSM_SPEED_LIMIT`, and `NONE`. If no fresh trusted limit exists, Android applies no overspeed penalty.

## Live telemetry

- HTTP fallback: `POST /v1/live/trips/{tripId}/frames`
- WebSocket: `/v1/live/trips/{tripId}`
- Dashboard: `GET /v1/live/dashboard`

All require bearer authentication. Query-string tokens are not accepted.

Errors use `{ "error": { "code": "...", "message": "..." } }`.
