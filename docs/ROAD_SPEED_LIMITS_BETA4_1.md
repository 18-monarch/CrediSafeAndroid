# CrediSafe Beta 4.1 — Automatic road-speed-limit hotfix

## Feature
The phone automatically obtains GPS positions. The backend map-matches the
latest GPS segment to an OpenStreetMap-derived Valhalla road edge, retrieves
**edge.speed_limit** (not inferred **edge.speed**), and returns the updated road
limit to Android. The speed-limit change is automatic; no user-entered speed limit.

Beta 4.1 fixes the previous 60-second/1-km Android refresh policy:
- First GPS position is a baseline; matching starts after >=10 m movement.
- Recheck after >=10 seconds AND >=100 m of movement, or 15 seconds with
  a trusted limit, or at most 30 seconds during a running journey.
- Android clears the previous limit while refreshing and discards older
  out-of-order map replies.
- A route change can therefore still be detected late; no navigation-grade SLA.
- GPS location must have accuracy <=30 m to initiate a map lookup.

## Trust decisions
Road limits can be displayed from mapping data, but the backend marks one
trusted for pilot scoring **only if all conditions hold**:

1. Runtime opt-in `VALHALLA_SPEED_LIMITS_TRUSTED=true`.
2. Valhalla edge has a numeric `speed_limit` between 10 and 140 km/h.
3. Valhalla edge reports `speed_type=tagged` (not `classified`).
4. GPS accuracy is <=20 m and map matching distance is <=15 m.
5. Road-match confidence is at least 0.86.
6. Signed, authenticated map-lookup proof can be issued with the backend secret.

If any condition fails, no new speeding penalty is allowed. No universal
urban/highway speed limit is assumed. Mapped tags may still be incorrect or
outdated; this is **not legal speed-limit certification**.

## Prevent wrong-road penalties
The backend signs the authenticated driver's matched road way ID, limit
and lookup timestamp using HMAC-SHA256. Android stores the road proof in a
speeding event's details. During offline sync, the server checks the proof
against the same driver and event time (max age 45 s), rather than using
the **last road of the trip**, which may have a different limit.

Telemetry corroboration still requires nearby recorded speed readings;
a signed map lookup is not proof that the driver actually exceeded a
legally posted sign. Old Beta 4 events lacking this proof are not given
automatic *server-side overspeed penalties* when the new backend is deployed.

## Offline / data
Trip recording and sensor-based events remain offline-first. Fresh road
lookups need connectivity. If disconnected or the map provider fails,
stale/missing speed limits do not produce new trusted overspeed penalties.
The server can check previously signed event proofs after connectivity
returns, without incorrectly rejecting them for being old *at sync time*.

## Rollout
This hotfix uses package `com.credisafe.mobile`, versionCode 29, versionName
`2.7.0-beta.4.1`, and the same signing keystore as v2.7.0-beta.4.
The previously published Beta 4 APK is immutable and retained.

Deploy the backend before publishing the APK, update the official website
only after signing/version continuity and CI checks pass.

## What is not verified
- Actual limits on Ahmedabad roads, particularly temporary signs, school
  zones, conditional limits, vehicle-class limits, and OSM data gaps.
- Public Valhalla availability/SLA, map recency and provider coverage.
- GPS variance, phone-specific behaviour, network latency, battery use,
  and event precision/recall from independently labelled journeys.
- Signed lookup proves the backend returned tagged OSM data, *not* that
  OSM matches a physical road sign. Never use the score for insurance,
  legal enforcement or financial eligibility.

## Safe test
Keep driver attention on the road; do not intentionally speed.
Compare tagged-map limits against actual posted signs during normal driving
(passenger notes or later video review), and separately exercise overspeeding
with synthetic/replayed telemetry in a controlled environment. Record
coverage, wrong-road matches and incorrect-limit rates.
