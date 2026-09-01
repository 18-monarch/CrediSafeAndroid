# CrediSafe Backend v2.7

TypeScript/Express backend for authentication, trip synchronization, live telemetry, OSM/Valhalla road context, and authoritative safety/XP finalization.

## Setup

```bash
npm ci
cp .env.example .env
npm run typecheck
npm run dev
```

Required environment:

```text
DATABASE_URL=postgresql://...
JWT_SECRET=<strong random value, at least 32 characters>
```

Optional:

```text
CORS_ORIGINS=https://your-dashboard.example
VALHALLA_BASE_URL=https://valhalla1.openstreetmap.de
VALHALLA_CLIENT_ID=CrediSafe-Open-Mobility/2.7
VALHALLA_SPEED_LIMITS_TRUSTED=false
```

Keep `VALHALLA_SPEED_LIMITS_TRUSTED=false` unless the selected graph is verified to expose reliable posted limits. A returned but untrusted limit is informational and cannot create an overspeed event.

## Database

For a new database, apply SQL migrations in filename order. Existing deployments must additionally apply `20260831000000_open_mobility_v2_7.sql`.

That migration adds password hashes/account metadata and unique trip-ledger keys. Legacy beta accounts can establish a password only from the original device/account UUID.

## Security behavior

- Passwords use bcrypt cost 12; plaintext passwords are never stored.
- JWTs require an issuer, audience, HS256, expiry, and a 32+ character secret.
- Auth and road endpoints are rate-limited.
- The live dashboard and WebSocket both require bearer authentication.
- Query-string WebSocket tokens are rejected.
- Road coordinates are sent in authenticated POST bodies rather than URLs.
- CORS is closed by default; configure explicit browser origins when needed.
- Server-computed score, XP, and reward points override Android previews.
- Compressed telemetry is size-limited, checksum-verified, timestamp-validated, and durably stored in PostgreSQL for the beta.

## Quality checks

```bash
npm run typecheck
npm run test:unit
```

The public Valhalla endpoint is intended only for fair-use beta validation. Production should self-host or contract an endpoint with operational guarantees.
