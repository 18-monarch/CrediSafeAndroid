# CrediSafe Rules Engine v2 — controlled pilot candidate

Status: **feature branch, not a released or calibrated production safety model**.
The published v2.7.0-beta.3 APK remains unchanged until CI and device acceptance gates pass.

## Why v2

The prior pipeline scored behaviour on-device, then the backend used the client score as
an input and could deduct the same penalties again. Sensor-rate quality also mixed
actual input and persisted samples. These defects made numeric scores unsuitable for
accuracy or funding claims.

## v2 flow

1. Android records a journey locally before attempting cloud sync.
2. Motion events are candidates until **at least 350 ms** of filtered acceleration
   supports them. Invalid GPS, missing bearing/orientation or abrupt sampling gaps
   suppress event confirmation. The existing 8-second cooldown prevents duplicate
   alerts. Orientation requirements can exclude real events and must be measured.
3. GPS readings outside the plausible speed range are flagged *before* any clamp
   and cannot generate overspeed penalties. A fresh trusted speed limit and **3 seconds of sustained speeding** are required before an overspeed event can be emitted.
4. The LOCAL behaviour score uses confirmed event counts only. Data quality remains
   a separate value, not an unsafe-driving penalty.
5. WorkManager still uploads trip, events, compressed raw samples and completion
   in order, retrying after connectivity is restored.
6. Backend refuses to finalize a trip for XP **without a verified, stored compressed
   telemetry upload**. It replays bounded samples for timestamp, GPS/motion quality,
   and basic event corroboration. Uncorroborated events do not subtract points.
7. The v2 server score starts at **100 once**, independent of any client-uploaded score.
   XP_RULESET_V1 caps and ledger idempotency are retained for backward compatibility.
8. A trip with server-measured GPS quality below 0.45 or telemetry quality below 0.60
   is marked REVIEW/INELIGIBLE for monetary-risk-free virtual rewards. This is a
   pilot criterion, not a scientifically proven dividing line.

## Initial pilot-only settings

| Parameter | v2 initial value |
| --- | --- |
| Motion EMA time constant | 180 ms |
| Motion candidate dwell | 350 ms |
| Maximum sample gap | 500 ms |
| Same-type event cooldown | 8 seconds |
| Sustained speeding evidence | 3 seconds of a fresh trusted road limit |
| Harsh braking | <= -2.5 m/s2 |
| Harsh acceleration | >= +2.7 m/s2 |
| Cornering | >= 3.5 m/s2 lateral and >= 25 km/h |
| GPS event accuracy gate | <= 30 m with fresh bearing/location |
| Backend event evidence window | +/- 1500 ms |
| Min corroborating persisted samples | 2 |
| Min uploaded samples | 10 |
| Confirmed pilot GPS quality | >= 0.45 |
| Confirmed pilot telemetry quality | >= 0.60 |

The thresholds are calibration hypotheses. **They are not crash-risk, legal compliance,
insurance or traffic-violation determinations.**

## What v2 does NOT prove

- Device sensor readings can still be forged by a compromised client.
- Server corroboration uses device-uploaded samples; it is not independent lab ground truth.
- The server's road speed limit is summarized at trip level. Speeding penalties cannot
  be represented as a verified legal violation without road-segment-specific evidence.
- Heading/phone orientation limitations can cause under-detection, especially if
  the phone moves during a journey.
- The current Android app intentionally does not upload invalid/rejected trips.
  A separate **opt-in, private** diagnostic upload is required for unbiased research.
- Local SQLite telemetry is not yet encrypted at rest, and database deletion/retention
  governance, formal consent, external security testing and scaling are incomplete.
- The score is a **prototype behaviour heuristic**. No real-world event precision,
  recall, route error, cross-device fairness or crash predictive validity is established.

## Safe field acceptance gates

- CI unit tests, TypeScript typecheck, Android test/build all pass.
- An existing beta.3 user installs the v2 candidate over beta.3; local trip history survives.
- Complete an eligible real-world trip, verify saved raw samples, sync to Neon, verify
  v2 evidence metadata and idempotent XP, then simulate lost connectivity and retry.
- Check GPS disabled, phone in pocket, GPS speed jump, mock location, road-provider
  unavailable, service killed and session expiration.
- Do not collect pilot participants until permission/consent language, access control,
  data retention/deletion, support contact and review workflow are approved.
- Collect consented, independently labelled trips; report false positives per 100 km,
  precision/recall, battery cost, sync success and platform-specific GPS error.
- Do not launch public monetary rewards or insurance-grade claims.

## Versioning and rollout

The backend stores `SAFETY_RULESET_V2_PILOT|XP_RULESET_V1` for new confirmed journeys.
Historical v1 finalizations remain final and are not recalculated. Android provides a
preliminary estimate and overwrites it with cloud-confirmed output on sync.

**Deployment order:** backend migration/tests (none needed for these changes) ->
internal Android debug candidate -> controlled real-device QA -> separate signed
beta.4 release using the existing keystore -> gradually invite pilot participants.

**Do not automatically tag, sign, or publish this feature branch as beta.3.**
