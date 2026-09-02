#!/usr/bin/env python3
from pathlib import Path
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
failures: list[str] = []
ANDROID = "{http://schemas.android.com/apk/res/android}"


def git(*args: str):
    try:
        return subprocess.run(
            ["git", *args],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError:
        return None


def require_text(path: str, tokens: list[str]) -> str:
    p = ROOT / path
    if not p.exists():
        failures.append(f"Missing required file: {path}")
        return ""
    text = p.read_text(encoding="utf-8", errors="replace")
    for token in tokens:
        if token not in text:
            failures.append(f"{path} missing required token: {token}")
    return text


# Parse every tracked/source XML file we ship.
for xml_file in sorted((ROOT / "app" / "src" / "main" / "res").rglob("*.xml")):
    try:
        ET.parse(xml_file)
    except Exception as exc:
        failures.append(f"Invalid XML {xml_file.relative_to(ROOT)}: {exc}")

manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
try:
    manifest = ET.parse(manifest_path).getroot()
except Exception as exc:
    failures.append(f"Invalid AndroidManifest.xml: {exc}")
    manifest = None

if manifest is not None:
    permissions = {n.attrib.get(ANDROID + "name") for n in manifest.findall("uses-permission")}
    required_permissions = {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACTIVITY_RECOGNITION",
        "com.google.android.gms.permission.ACTIVITY_RECOGNITION",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
    }
    for item in sorted(required_permissions - permissions):
        failures.append(f"Missing manifest permission: {item}")

    app = manifest.find("application")
    if app is None:
        failures.append("Missing <application>.")
    else:
        if app.attrib.get(ANDROID + "usesCleartextTraffic") != "false":
            failures.append("Cleartext traffic must be disabled.")
        if app.attrib.get(ANDROID + "allowBackup") != "false":
            failures.append("App backup must be disabled.")

        maps_nodes = [n for n in app.findall("meta-data") if n.attrib.get(ANDROID + "name") == "com.google.android.geo.API_KEY"]
        if maps_nodes:
            failures.append("Google Maps metadata must not be present in the Open Mobility build.")

gradle = require_text(
    "app/build.gradle.kts",
    [
        'applicationId = "com.credisafe.mobile"',
        "minSdk = 26",
        "targetSdk = 37",
        "compileSdk = 37",
        "CREDISAFE_VERSION_NAME",
        "CREDISAFE_VERSION_CODE",
        "DISTRIBUTION_CHANNEL",
        "MAP_STYLE_URL",
        "play-services-location",
        "org.maplibre.gl:android-sdk",
    ],
)

gradle_properties = require_text(
    "gradle.properties",
    [
        "CREDISAFE_VERSION_CODE=26",
        "CREDISAFE_VERSION_NAME=2.7.0-beta.2",
        "CREDISAFE_API_RELEASE_URL=https://credisafeandroid.onrender.com/v1/",
    ],
)

gitignore = require_text(
    ".gitignore",
    ["local.properties", ".env", "signing.properties", ".signing/", "release-output/"],
)

# Local secret/config files are allowed in a developer checkout, but they must
# be ignored and untracked. A clean overlay ZIP will not contain them.
inside = git("rev-parse", "--is-inside-work-tree")
git_ok = inside is not None and inside.returncode == 0
for rel in ["local.properties", "signing.properties", "backend/.env"]:
    p = ROOT / rel
    if not p.exists() or not git_ok:
        continue
    tracked = git("ls-files", "--error-unmatch", "--", rel)
    if tracked is not None and tracked.returncode == 0:
        failures.append(f"LOCAL SECRET/CONFIG IS TRACKED BY GIT: {rel}")
    ignored = git("check-ignore", "-q", "--", rel)
    if ignored is not None and ignored.returncode != 0:
        failures.append(f"Local secret/config is not ignored: {rel}")

required_files = [
    "app/src/main/java/com/credisafe/mobile/domain/Mobility.kt",
    "app/src/main/java/com/credisafe/mobile/domain/RoadContext.kt",
    "app/src/main/java/com/credisafe/mobile/domain/TripValidityEngine.kt",
    "app/src/main/java/com/credisafe/mobile/service/MobilityActivityManager.kt",
    "app/src/main/java/com/credisafe/mobile/service/MobilityActivityReceiver.kt",
    "app/src/main/java/com/credisafe/mobile/data/RoadContextRepository.kt",
    "backend/src/services/roadContextService.ts",
    "backend/migrations/20260829000000_mobility_intelligence_v2_6.sql",
    "backend/migrations/20260831000000_open_mobility_v2_7.sql",
    "app/src/main/java/com/credisafe/mobile/OpenMobilityMap.kt",
    "START_HERE_V2_7.md",
    "FINAL_VALIDATION_REPORT_V2_7.md",
    "docs/OPEN_MOBILITY_V2_7.md",
    "docs/SECURITY_V2_7.md",
    "docs/DISTRIBUTION_V2_7.md",
    ".github/workflows/android-beta-release.yml",
    "scripts/build-beta.ps1",
    "scripts/create-beta-keystore.ps1",
]
for rel in required_files:
    if not (ROOT / rel).exists():
        failures.append(f"Missing required Open Mobility file: {rel}")

map_ui = require_text(
    "app/src/main/java/com/credisafe/mobile/OpenMobilityMap.kt",
    ["MapLibre.getInstance", "BuildConfig.MAP_STYLE_URL", "PolylineOptions"],
)

mobility = require_text(
    "app/src/main/java/com/credisafe/mobile/domain/Mobility.kt",
    ["POSSIBLE_RAIL_TRANSIT", "NON_DRIVING_BLOCK_CONFIDENCE", "isFresh("],
)
road = require_text(
    "app/src/main/java/com/credisafe/mobile/domain/RoadContext.kt",
    ["speedLimitTrusted", "No fresh trusted speed limit", "ZoneProfileAccumulator"],
)
validity = require_text(
    "app/src/main/java/com/credisafe/mobile/domain/TripValidityEngine.kt",
    ["NOISE", "POSSIBLE_RAIL_TRANSIT", "shouldSync"],
)
xp_engine = require_text(
    "app/src/main/java/com/credisafe/mobile/domain/XpEngine.kt",
    ['const val VERSION = "2.7"', "Per-trip XP cap"],
)
service = require_text(
    "app/src/main/java/com/credisafe/mobile/service/TelemetryForegroundService.kt",
    [
        "MobilityActivityManager",
        "RoadContextRepository",
        "ROAD_CONTEXT_MIN_REFRESH_MS",
        "TripValidityEngine.assess",
    ],
)

backend_env = require_text(
    "backend/.env.example",
    ["VALHALLA_BASE_URL=", "VALHALLA_CLIENT_ID=", "VALHALLA_SPEED_LIMITS_TRUSTED=false"],
)

road_service = require_text(
    "backend/src/services/roadContextService.ts",
    ["trace_attributes", "X-Client-Id", "VALHALLA_SPEED_LIMITS_TRUSTED", "osm-way-"],
)
auth_service = require_text(
    "backend/src/auth/authService.ts",
    ["bcrypt.hash", "bcrypt.compare", "issuer: 'credisafe-api'", "audience: 'credisafe-android'"],
)
if "|| 'secret'" in auth_service or '|| "secret"' in auth_service:
    failures.append("Backend authentication must not ship with a default JWT secret.")

local_example = require_text(
    "local.properties.example",
    [
        "CREDISAFE_API_DEBUG_URL=https://credisafeandroid.onrender.com/v1/",
        "CREDISAFE_API_RELEASE_URL=https://credisafeandroid.onrender.com/v1/",
    ],
)
if "GOOGLE_MAPS_API_KEY" in local_example:
    failures.append("local.properties.example must not reference a Google Maps key.")

migration = require_text(
    "backend/migrations/20260829000000_mobility_intelligence_v2_6.sql",
    [
        "mobility_mode",
        "road_match_ratio",
        "idx_events_trip_time_type_unique",
    ],
)

workflow = require_text(
    ".github/workflows/android-beta-release.yml",
    [
        "BETA_KEYSTORE_BASE64",
        "assembleRelease",
        "bundleRelease",
    ],
)

active_sources = [
    ROOT / "app/build.gradle.kts",
    ROOT / "app/src/main/AndroidManifest.xml",
    ROOT / "backend/src/services/roadContextService.ts",
    ROOT / ".github/workflows/android-beta-release.yml",
]
for source in active_sources:
    text = source.read_text(encoding="utf-8", errors="replace")
    for legacy in ["GOOGLE_MAPS_API_KEY", "play-services-maps", "maps-compose", "roads.googleapis.com"]:
        if legacy in text:
            failures.append(f"Legacy Google Maps dependency remains in {source.relative_to(ROOT)}: {legacy}")

# Basic template/json sanity where applicable.
for json_file in sorted(ROOT.rglob("*.json")):
    if any(part in {"node_modules", "build"} for part in json_file.parts):
        continue
    try:
        json.loads(json_file.read_text(encoding="utf-8"))
    except Exception as exc:
        failures.append(f"Invalid JSON {json_file.relative_to(ROOT)}: {exc}")

# Obvious accidental secret/placeholders in shipped source.
for file in ROOT.rglob("*"):
    if not file.is_file():
        continue
    if any(part in {".git", "node_modules", "build", ".gradle", ".signing", "release-output"} for part in file.parts):
        continue
    if file.suffix.lower() not in {".kt", ".kts", ".ts", ".md", ".xml", ".properties", ".yml", ".yaml", ".example", ".sql"}:
        continue
    text = file.read_text(encoding="utf-8", errors="replace")
    if "YOUR_API_KEY_HERE" in text:
        failures.append(f"Legacy API key placeholder remains in {file.relative_to(ROOT)}.")

if failures:
    print("CrediSafe unified verification FAILED:")
    for failure in failures:
        print(" -", failure)
    sys.exit(1)

print("CrediSafe v2.7.0-beta.2 Open Mobility verification PASSED.")
print("MapLibre + OSM/Valhalla + secure auth + Trip Validity + server authority are present.")
print("Local config/secrets may exist locally only when untracked and ignored.")
