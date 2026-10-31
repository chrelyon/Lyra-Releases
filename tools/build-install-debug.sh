#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 || ( $# -eq 1 && "${1}" != "--install" ) ]]; then
    echo "Uso: $0 [--install]" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "[Lyra] Build debug..."
./gradlew :app:assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
    echo "APK Debug non trovato: $APK" >&2
    exit 1
fi
echo "[Lyra] APK Debug: $APK"

if [[ "${1:-}" == "--install" ]]; then
    if ! command -v adb >/dev/null 2>&1; then
        echo "ADB non disponibile. Installa Android Platform Tools e collega un dispositivo autorizzato." >&2
        exit 1
    fi
    echo "[Lyra] Installazione ADB richiesta esplicitamente (--install)..."
    adb install -r "$APK"
    echo "[Lyra] Installazione completata."
else
    echo "[Lyra] Solo compilazione: nessuna installazione ADB."
fi
