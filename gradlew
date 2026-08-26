#!/usr/bin/env sh
set -eu
V=9.5.0
D="$HOME/.gradle/wrapper/dists/gradle-$V"
if [ ! -x "$D/gradle-$V/bin/gradle" ]; then mkdir -p "$D"; curl -fsSL "https://services.gradle.org/distributions/gradle-$V-bin.zip" -o /tmp/g.zip; unzip -q /tmp/g.zip -d "$D"; fi
exec "$D/gradle-$V/bin/gradle" "$@"
