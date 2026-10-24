# CrediSafe Android - Authenticated Live Telemetry Recovery

This branch fixes the observed production-beta failure:
`Live stream needs an authenticated session.`

## What changed
- 12-hour access tokens now have rotating, device-bound 30-day refresh tokens.
- Refresh tokens are random 256-bit opaque credentials. Only SHA-256 hashes
  are stored in PostgreSQL, encrypted values on Android.
- Expired tokens are renewed during app entry, WebSocket reconnect, and
  background trip synchronization; multiple Android auth clients coordinate
  rotation through a process-wide mutex.
- Invalid refresh sessions require explicit login; temporary network problems
  do **not** erase recorded trips.
- Local journey recording and cloud live-stream states are no longer conflated:
  **RECORDING** means local sensors are active, **CLOUD LIVE** requires a
  connected WebSocket.
- Health diagnostics support retry, GPS accuracy is formatted to one decimal,
  and signing out during an active journey is disabled.
- Logout clears credentials immediately and attempts server-side refresh-token
  revocation (best-effort if offline).

## Required deployment order - do not skip
1. Back up Neon database and verify a restore.
2. Apply `backend/migrations/20261024000000_refresh_sessions.sql`
   to the **correct CrediSafe Android** database (after older migrations).
3. Verify Render `DATABASE_URL`, `JWT_SECRET` and connectivity.
4. Deploy the backend from this branch **before** updating any Android APK.
5. Test `/health`, then verify `/v1/auth/session` returns a
   `refreshToken` after legitimate login (do not publish the token).
6. Test `/v1/auth/refresh` rotating a token, and ensure the previous
   refresh token cannot be used again.
7. Check login, token refresh, logout, offline trip completion, sync retry,
   WebSocket LIVE, server wakeup, device reboot and background GPS on a real
   device.
8. Build a correctly signed Android beta with the **existing keystore** and
   a higher `versionCode`. Distribute to testers only after CI passes.

## Compatibility and honest limits
- Existing beta accounts with only a now-expired access token must sign in
  once after updating to establish a refresh token.
- This patch does not auto-generate a signed APK or change the deployed
  Render service or Neon schema.
- In-memory live streaming is not a substitute for persisted trip telemetry.
- Live road zone/matching can remain UNKNOWN until enough GPS samples arrive;
  FOSSGIS public Valhalla is not a production SLA-backed service.
- Android encrypted preferences must be retained across in-place upgrades.
- Production launch still requires a tested migration, privacy/account
  deletion UX, external security validation, and road-safety calibration.

## CI and manual acceptance
Run `npm ci && npm run typecheck && npm run test:unit` in `backend`.
Run `./gradlew test assembleDebug` with an Android SDK.
Do not equate CI success with a verified live-device release.
