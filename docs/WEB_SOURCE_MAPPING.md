# CrediSafe Web → Native Android

The supplied `CrediSafe-main.zip` is the source of truth for this mobile conversion.

The mobile web composition explicitly defines the product story:
- “Drive safe. Earn more.”
- One transparent result
- Explainable XP
- Journey intelligence
- Useful reward progress
- Positive recognition
- User-owned data

The supplied web MVP also defines the native-facing product concepts around Trip → Score → XP → Reward → Rank and an XP engine version 2.0.

The Android build preserves those concepts and adds native capabilities that a browser cannot provide consistently:
- foreground phone telemetry
- accelerometer
- gyroscope
- linear acceleration when available
- location collection
- local trip/sensor/event storage

Video verification is intentionally not included in the Android telemetry pilot; the supplied web architecture keeps the vision service separate from the GPS scoring path.
