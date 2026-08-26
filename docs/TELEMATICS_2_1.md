# CrediSafe Telematics 2.1

## Sensor stack
- Fused GNSS location at approximately 1 Hz with high accuracy and minimum distance filtering.
- Accelerometer, gyroscope, linear acceleration and rotation vector at up to 20 Hz.
- Rotation-vector based world-frame acceleration.
- GPS-bearing based vehicle-frame longitudinal/lateral acceleration.

## Data quality
The service calculates GPS quality, sensor sample quality, suspicious location jumps and a combined telemetry-quality score.

## Event detection
The pilot detects:
- minor overspeed
- major overspeed
- harsh braking
- harsh acceleration
- aggressive cornering

Events have type, severity, confidence, speed, longitudinal/lateral acceleration and an explanation.

Cooldowns prevent a single manoeuvre from generating a burst of duplicate events.

## Data storage
Sensor records are buffered and inserted in batches instead of performing a SQLite write for every sensor callback. This reduces storage overhead while preserving approximately 10 Hz research samples.

## Trip finalization
At stop time the service:
1. flushes pending sensor samples;
2. calculates GPS/sensor quality;
3. detects anti-gaming anomalies;
4. calculates safety score;
5. resolves the daily streak;
6. calculates transparent XP;
7. converts XP to reward points;
8. stores the full breakdown and quality metadata.

This is the pilot baseline for real-world calibration, not a certified insurance or safety rating.
