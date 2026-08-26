# CrediSafe Backend

Real REST API for CrediSafe Telematics.

## Tech Stack
- TypeScript / Node.js
- Express
- Knex.js
- PostgreSQL (Neon)

## Setup

1. `cd backend`
2. `npm install`
3. Create `.env` from `.env.example`
4. Set `DATABASE_URL` and `JWT_SECRET`
5. Run migrations: (Manual setup for Neon for now)
   ```bash
   psql $DATABASE_URL -f migrations/20260825000000_initial_schema.sql
   ```

## Development
```bash
npm run dev
```

## E2E Test
```bash
# Ensure server is running at http://localhost:3000
npx ts-node-dev tests/e2e.ts
```

## API Summary
- `POST /v1/auth/session`: Create session/user from deviceId.
- `POST /v1/trips`: Create/Update trip summary.
- `POST /v1/trips/{tripId}/events`: Batch upload driving events.
- `POST /v1/trips/{tripId}/telemetry`: Upload compressed 50Hz sensor samples.
- `POST /v1/trips/{tripId}/complete`: Authoritative XP/Points calculation.
- `GET /v1/trips`: List user's trips.
- `POST /v1/sync/ack`: Acknowledge sync status.

## Conflict Resolution
- `tripId` (UUID) is the idempotency key.
- Server results are authoritative and stored in `xp_ledger`.
- Duplicate uploads return `200 OK` without creating duplicates.
