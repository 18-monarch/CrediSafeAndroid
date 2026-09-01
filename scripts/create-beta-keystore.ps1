param()
$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$SigningDir = Join-Path $ProjectRoot ".signing"
$Keystore = Join-Path $SigningDir "credisafe-beta.keystore"
$PropertiesFile = Join-Path $ProjectRoot "signing.properties"
$Alias = "credisafe-beta"

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw "keytool was not found. Use JDK 17 and make sure JAVA_HOME/bin is on PATH."
}
$HasKeystore = Test-Path $Keystore
$HasProperties = Test-Path $PropertiesFile

if ($HasKeystore -and $HasProperties) {
    Write-Host "Beta signing material already exists. Nothing was overwritten." -ForegroundColor Yellow
    Write-Host "Reuse these files for every future CrediSafe beta update."
    exit 0
}
if ($HasKeystore -xor $HasProperties) {
    throw "Partial signing state detected. One signing file exists but the other is missing. Restore the missing v2.5 signing file from backup; do NOT generate a replacement key."
}

New-Item -ItemType Directory -Force -Path $SigningDir | Out-Null
$Password = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))

& keytool -genkeypair -v -noprompt `
    -keystore $Keystore -storetype PKCS12 `
    -storepass $Password -keypass $Password `
    -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 `
    -dname "CN=CrediSafe Beta, OU=Mobile, O=CrediSafe, L=Pilot, ST=Pilot, C=IN"

if ($LASTEXITCODE -ne 0) { throw "keytool failed." }

@"
storeFile=.signing/credisafe-beta.keystore
storePassword=$Password
keyAlias=$Alias
keyPassword=$Password
"@ | Set-Content -Path $PropertiesFile -Encoding UTF8

Write-Host ""
Write-Host "CrediSafe beta signing is ready." -ForegroundColor Green
Write-Host "Back up BOTH files securely:"
Write-Host "  $Keystore"
Write-Host "  $PropertiesFile"
Write-Host ""
Write-Host "Certificate fingerprints:"
& keytool -list -v -keystore $Keystore -storepass $Password -alias $Alias |
    Select-String -Pattern "SHA1:|SHA256:"
