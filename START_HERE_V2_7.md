# Start Here — CrediSafe v2.7 Open Mobility

1. Do not add a Google Maps key. v2.7 uses MapLibre and OpenFreeMap.
2. Preserve your existing beta keystore and `signing.properties`.
3. Copy `local.properties.example` to `local.properties` and preserve `sdk.dir`.
4. Apply the v2.7 backend migration to Neon.
5. Set Render `JWT_SECRET` to a random 32+ character value.
6. Set `VALHALLA_CLIENT_ID`; use the public default only for fair-use beta testing.
7. Run `python scripts/verify-distribution.py`.
8. Run `npm ci && npm run typecheck && npm run test:unit` inside `backend`.
9. Build with `scripts/build-beta.ps1` on the machine that has the existing signing key.
10. Install the release-output APK over the prior beta and run the acceptance tests in the README.

Do not commit `.env`, `local.properties`, `signing.properties`, the keystore, APK/AAB files, or telemetry exports.
