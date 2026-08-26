# CrediSafe Android — Production-Grade Telematics Platform

CrediSafe is a native Android client for high-fidelity journey intelligence. It captures real phone telemetry (50Hz), processes safety events locally, and synchronizes results with a cloud backend.

## Key Features
- **Elite Telemetry**: Sensor fusion with world-to-vehicle frame alignment.
- **Explainable Scoring**: Deterministic safety scoring and tiered XP engine.
- **Offline First**: Full recording and scoring capability without internet.
- **Cloud Sync**: Reliable background synchronization using WorkManager and Retrofit.
- **Expert Diagnostics**: Real-time sensor auditing and hardware health monitoring.

## Technical Stack
- **Language**: Kotlin 2.3.21
- **UI**: Jetpack Compose (BOM 2026.08.00)
- **Architecture**: MVI/Local-First with WorkManager
- **Networking**: Retrofit 2.11 + OkHttp 5.5
- **Persistence**: SQLite (Room-ready schema)
- **Compliance**: Android 14+ Foreground Service Location compliant.

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Telematics Math](docs/TELEMATICS_2_1.md)
- [XP Engine Logic](docs/XP_ENGINE_2_1.md)

## Development
```bash
./gradlew build
./gradlew test
```

### API Configuration
The API base URL is configured in `gradle.properties`. To override it for your local environment (without checking it into git), add the following to `local.properties`:
```properties
CREDISAFE_API_DEBUG_URL=https://your-dev-api.com/v1/
CREDISAFE_API_RELEASE_URL=https://your-prod-api.com/v1/
```
The app uses HTTPS by default. WebSocket connections automatically use the same host (switched to `wss://`).
