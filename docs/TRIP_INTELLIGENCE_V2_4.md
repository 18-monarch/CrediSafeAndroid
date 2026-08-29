# CrediSafe Trip Intelligence v2.4

## Decision order

1. Collect telemetry locally.
2. Resolve road context opportunistically; never block recording on network.
3. On Stop, calculate telemetry quality and anti-gaming flags.
4. Classify the recording with `TripValidityEngine`.
5. Only `ELIGIBLE` trips enter XP/reward/streak/cloud-sync progression.
6. `NOISE` is hidden and automatically purged after the diagnostic retention window.
7. `INVALID`/`SUSPICIOUS` records remain local for explanation and calibration.

## Road safety principle

Overspeed is emitted only when `RoadRuleEngine` has:
- fresh road context,
- `speedLimitTrusted = true`,
- context confidence >= 0.70,
- a concrete speed limit.

Unknown/unlicensed/unavailable speed-limit data produces no overspeed penalty.

## Google architecture

Android Maps SDK:
- visual map, route polyline, live position.
- API key configured by manifest placeholder from local/CI properties.

Backend Google services:
- Roads `nearestRoads` for road-segment identity and snapped coordinates.
- Geocoding for road/address/jurisdiction context.
- Roads Speed Limits only when the project has the required entitlement.

The server key is never shipped in the APK.
