#!/usr/bin/env bash
# Build di sviluppo: test JVM + APK debug, nessuna chiave release.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
chmod +x ./gradlew
./gradlew :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
printf 'APK debug: %s\n' "$APK"
printf '%s\n' 'La firma debug non aggiorna una APK release installata.'
