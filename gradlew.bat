@echo off
setlocal
set "GRADLE_VERSION=9.5.0"
set "DIST=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"
set "HOME=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin"
if not exist "%HOME%\gradle-%GRADLE_VERSION%\bin\gradle.bat" (
  if not exist "%HOME%" mkdir "%HOME%"
  set "ZIP=%TEMP%\gradle-%GRADLE_VERSION%.zip"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%DIST%' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 1
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%HOME%' -Force"
  if errorlevel 1 exit /b 1
)
call "%HOME%\gradle-%GRADLE_VERSION%\bin\gradle.bat" %*
