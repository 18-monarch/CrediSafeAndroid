# Distribution v2.7

The package remains `com.credisafe.mobile` and version code advances to 25. Reuse the original beta signing certificate to support install-over updates.

The release build script verifies source safety, runs unit tests, creates signed APK/AAB files, and writes SHA-256 hashes to `release-output`. GitHub Actions requires only beta signing secrets; no map-provider key is required.

Never distribute or commit the keystore, signing passwords, backend `.env`, local Android properties, raw telemetry, or unredacted user data.
