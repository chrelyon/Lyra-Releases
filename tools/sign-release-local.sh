#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INPUT=""; KEYSTORE=""; ALIAS=""; BUILD_TOOLS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) INPUT="${2:?}"; shift 2 ;;
    --keystore) KEYSTORE="${2:?}"; shift 2 ;;
    --alias) ALIAS="${2:?}"; shift 2 ;;
    --build-tools) BUILD_TOOLS="${2:?}"; shift 2 ;;
    *) echo "Uso: $0 --input APK --keystore FILE --alias NOME --build-tools DIR" >&2; exit 2 ;;
  esac
done
[[ -f "$INPUT" && -f "$KEYSTORE" && -n "$ALIAS" && -x "$BUILD_TOOLS/apksigner" && -x "$BUILD_TOOLS/zipalign" && -x "$BUILD_TOOLS/aapt" ]] || {
  echo "Controlla APK, keystore, alias e directory Android build-tools." >&2; exit 2;
}
VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -n1)"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$ROOT/app/build.gradle.kts" | head -n1)"
[[ -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] || exit 2
BADGING="$("$BUILD_TOOLS/aapt" dump badging "$INPUT")"
PACKAGE_LINE="${BADGING%%$'\n'*}"
[[ "$PACKAGE_LINE" == *"name='app.lyra.music'"* && "$PACKAGE_LINE" == *"versionCode='$VERSION_CODE'"* && "$PACKAGE_LINE" == *"versionName='$VERSION_NAME'"* ]] || {
  echo "L’APK non corrisponde al pacchetto e alla versione dei sorgenti." >&2; exit 2;
}
DIST="$ROOT/dist/signed"
mkdir -p "$DIST"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$INPUT" "$TEMP_DIR/aligned.apk"
# apksigner prompts locally. Passwords are never arguments, files or script output.
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" --out "$TEMP_DIR/signed.apk" "$TEMP_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$TEMP_DIR/signed.apk"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$TEMP_DIR/signed.apk"
APK_NAME="Lyra-$VERSION_NAME.apk"
cp "$TEMP_DIR/signed.apk" "$DIST/$APK_NAME"
SHA256="$(sha256sum "$DIST/$APK_NAME" | awk '{print $1}')"
cat > "$DIST/update.json" <<JSON
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "apkName": "$APK_NAME",
  "sha256": "$SHA256",
  "mandatory": false,
  "notes": "Lyra $VERSION_NAME: cerca avanzato artisti e album; catalogo server unificato."
}
JSON
echo "APK firmato verificato e update.json: $DIST"
echo "Per aggiornare l’app installata occorre la stessa chiave di firma usata in precedenza."
