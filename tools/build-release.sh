#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -n1)"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -n1)"

if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "Impossibile leggere versionName/versionCode" >&2
  exit 1
fi

cd "$ROOT"
if [[ "${1:-}" == "--unsigned" ]]; then
  ./gradlew assembleRelease -PlyraUnsignedRelease=true
  mkdir -p "$ROOT/dist/unsigned"
  cp "$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk" "$ROOT/dist/unsigned/Lyra-$VERSION_NAME-unsigned.apk"
  (
    cd "$ROOT/dist/unsigned"
    sha256sum "Lyra-$VERSION_NAME-unsigned.apk" > SHA256SUMS.txt
  )
  echo "APK non firmato: dist/unsigned/Lyra-$VERSION_NAME-unsigned.apk"
  echo "Firma locale necessaria; nessun update.json pubblicabile è stato generato."
  exit 0
fi
if [[ $# -gt 0 ]]; then echo "Uso: $0 [--unsigned]" >&2; exit 2; fi
./gradlew assembleRelease

SOURCE="$ROOT/app/build/outputs/apk/release/app-release.apk"
DIST="$ROOT/dist"
mkdir -p "$DIST"
APK_NAME="Lyra-$VERSION_NAME.apk"
cp "$SOURCE" "$DIST/$APK_NAME"
SHA256="$(sha256sum "$DIST/$APK_NAME" | awk '{print $1}')"

cat > "$DIST/update.json" <<JSON
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "apkName": "$APK_NAME",
  "sha256": "$SHA256",
  "mandatory": false,
  "notes": "Aggiornamento Lyra $VERSION_NAME"
}
JSON

echo "Release pronta:"
echo "  $DIST/$APK_NAME"
echo "  $DIST/update.json"
echo "SHA-256: $SHA256"
echo "Carica entrambi come asset della GitHub Release con tag v$VERSION_NAME."
