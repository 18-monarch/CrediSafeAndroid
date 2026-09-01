param([switch]$SkipTests)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $ProjectRoot

function Read-SimpleProperties([string]$Path) {
    $result = @{}
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) { return }
        $parts = $line -split "=", 2
        if ($parts.Count -eq 2) {
            $result[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    return $result
}

if (-not (Test-Path "signing.properties")) {
    throw "signing.properties is missing. Restore the EXISTING v2.5 beta signing files, or run create-beta-keystore.ps1 only if you have never created a CrediSafe beta key."
}

$Signing = Read-SimpleProperties "signing.properties"
foreach ($name in @("storeFile", "storePassword", "keyAlias", "keyPassword")) {
    if (-not $Signing.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($Signing[$name])) {
        throw "signing.properties is incomplete: missing $name."
    }
}

$StoreFileValue = $Signing["storeFile"]
$StoreFile = if ([System.IO.Path]::IsPathRooted($StoreFileValue)) {
    $StoreFileValue
} else {
    Join-Path $ProjectRoot ($StoreFileValue -replace "/", [System.IO.Path]::DirectorySeparatorChar)
}
if (-not (Test-Path $StoreFile)) {
    throw "Beta keystore not found: $StoreFile. Restore the SAME v2.5 signing key; do not generate a replacement if testers already installed v2.5."
}

$GradleProperties = Read-SimpleProperties "gradle.properties"
if (-not $GradleProperties.ContainsKey("CREDISAFE_VERSION_NAME")) {
    throw "CREDISAFE_VERSION_NAME is missing from gradle.properties."
}
if (-not $GradleProperties.ContainsKey("CREDISAFE_VERSION_CODE")) {
    throw "CREDISAFE_VERSION_CODE is missing from gradle.properties."
}
$VersionName = $GradleProperties["CREDISAFE_VERSION_NAME"]
$VersionCode = $GradleProperties["CREDISAFE_VERSION_CODE"]

Write-Host "Running CrediSafe distribution preflight..." -ForegroundColor Cyan
& python ".\scripts\verify-distribution.py"
if ($LASTEXITCODE -ne 0) { throw "Distribution preflight failed." }

$Tasks = @("clean")
if (-not $SkipTests) { $Tasks += "test" }
$Tasks += @("assembleRelease", "bundleRelease")

Write-Host "Building CrediSafe $VersionName (versionCode $VersionCode)..." -ForegroundColor Cyan
& .\gradlew.bat @Tasks
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

$Apk = "app\build\outputs\apk\release\app-release.apk"
$Aab = "app\build\outputs\bundle\release\app-release.aab"
if (-not (Test-Path $Apk)) { throw "Signed release APK not found: $Apk" }
if (-not (Test-Path $Aab)) { throw "Release AAB not found: $Aab" }

New-Item -ItemType Directory -Force -Path "release-output" | Out-Null
$SafeVersion = $VersionName -replace '[^A-Za-z0-9._-]', '-'
$FinalApk = "release-output\CrediSafe-$SafeVersion.apk"
$FinalAab = "release-output\CrediSafe-$SafeVersion.aab"
Copy-Item $Apk $FinalApk -Force
Copy-Item $Aab $FinalAab -Force

$ApkHash = (Get-FileHash $FinalApk -Algorithm SHA256).Hash.ToLowerInvariant()
$AabHash = (Get-FileHash $FinalAab -Algorithm SHA256).Hash.ToLowerInvariant()
@"
CrediSafe $VersionName
versionCode $VersionCode
SHA-256
$ApkHash  $(Split-Path $FinalApk -Leaf)
$AabHash  $(Split-Path $FinalAab -Leaf)
"@ | Set-Content "release-output\SHA256SUMS.txt" -Encoding UTF8

Write-Host ""
Write-Host "BUILD READY" -ForegroundColor Green
Write-Host "APK: $FinalApk"
Write-Host "AAB: $FinalAab"
Write-Host "Hashes: release-output\SHA256SUMS.txt"
