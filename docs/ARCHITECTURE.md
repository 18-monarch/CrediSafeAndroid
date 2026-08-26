# CrediSafe Android Architecture

## System Overview
CrediSafe is a native Android telematics platform designed for high-fidelity journey recording, safety scoring, and cloud synchronization.

## Architecture Layers

### 1. Telemetry Engine (`com.credisafe.mobile.service`)
- **TelemetryForegroundService**: The core background service that manages sensor registration and location updates. It performs real-time sensor fusion and world-to-vehicle frame rotations.
- **Sensor Fusion**: Uses Accelerometer, Gyroscope, and Rotation Vector to derive longitudinal and lateral forces independent of device orientation.

### 2. Domain & Scoring (`com.credisafe.mobile.domain`)
- **SafetyEngine**: Deterministic logic for calculating safety scores based on detected events (braking, acceleration, cornering, speeding).
- **XpEngine**: A versioned engine that calculates lifetime XP and reward points. Includes eligibility gates and anti-gaming protections.
- **TelematicsQuality**: A formal model that audits the integrity of the collected data (GPS accuracy, sample rate, mock detection).

### 3. Data & Persistence (`com.credisafe.mobile.data`)
- **Local SQLite**: Reliable persistence for trips, events, and raw sensor samples.
- **TripSyncManager**: Manages the lifecycle of trip data from local-only to cloud-confirmed.
- **AuthManager**: Abstracts user identity and secure token handling.

### 4. Cloud Sync (`com.credisafe.mobile.service.SyncWorker`)
- **WorkManager**: Ensures reliable, background synchronization of completed journeys.
- **Retrofit/OkHttp**: Type-safe REST API communication with the cloud backend.
- **Idempotency**: Uses client-generated UUIDs to prevent duplicate records on the server.

## Data Flow
1. **Journey Active**: Raw sensors (50Hz) -> Processing -> Local DB (Samples).
2. **Journey Complete**: Final Scoring -> XP Engine -> Local DB (Summary).
3. **Sync Queue**: WorkManager -> SyncWorker -> REST API -> PostgreSQL Backend.
4. **Server Response**: Server-authoritative XP/Points returned and stored locally as `SERVER CONFIRMED`.

## Resilience
- **Offline First**: The app requires zero connectivity to record and score a trip.
- **Persistence**: All raw data is stored locally before any network attempt.
- **Retry Policy**: Exponential backoff for failed uploads.
