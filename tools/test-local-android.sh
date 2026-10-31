#!/usr/bin/env bash
# Local Ubuntu build/check only. Never reads signing files, wipes devices or publishes releases.
set -euo pipefail
ROOT=/home/chrelyon/AndroidTools
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK=false
SERIAL=""
AVD=""
usage() {
  cat <<'TXT'
Uso: ./tools/test-local-android.sh [--root DIR] [--project DIR] [--check]
                                     [--avd NOME] [--install-emulator emulator-5554]
Default: AndroidTools=/home/chrelyon/AndroidTools; progetto=cartella dello script.
--check controlla gli strumenti senza compilare o avviare emulatori.
La build normale esegue test JVM e assembleDebug; non firma APK ufficiali.
L'installazione è consentita solo su emulatori, mai sul telefono con i dati reali.
TXT
}
while (($#)); do
  case "$1" in
    --root|--project|--avd|--install-emulator)
      (($#>=2)) || { usage; exit 2; }
      case "$1" in --root) ROOT="$2";; --project) PROJECT="$2";; --avd) AVD="$2";; --install-emulator) SERIAL="$2";; esac
      shift 2;;
    --check) CHECK=true; shift;;
    --help|-h) usage; exit 0;;
    *) usage; exit 2;;
  esac
done
if [[ ! -f "$PROJECT/app/build.gradle.kts" || ! -f "$PROJECT/gradlew" ]]; then
  if ! $CHECK; then echo 'Progetto Android non trovato: specificare --project.' >&2; exit 1; fi
  echo 'Progetto non selezionato. Wrapper Gradle presenti in AndroidTools:'
  if [[ -d "$ROOT" ]]; then find "$ROOT" -maxdepth 5 -type f -name gradlew -print; fi
  PROJECT='da selezionare con --project'
fi
# Inspect SDK/tool paths only, never local.properties or signing configuration.
SDK=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$ROOT" "$ROOT/sdk" "$ROOT/android-sdk" "$ROOT/android-sdk-linux" "$ROOT/Android/Sdk" "${HOME}/Android/Sdk"; do
  if [[ -n "$candidate" && -f "$candidate/platform-tools/adb" ]]; then SDK="$candidate"; break; fi
done
if [[ -z "$SDK" && -d "$ROOT" ]]; then
  FOUND_ADB="$(find "$ROOT" -maxdepth 5 -type f -path '*/platform-tools/adb' -print -quit)"
  if [[ -n "$FOUND_ADB" ]]; then SDK="$(dirname "$(dirname "$FOUND_ADB")")"; fi
fi
if [[ -z "$SDK" ]] && command -v adb >/dev/null; then
  SDK="$(dirname "$(dirname "$(readlink -f "$(command -v adb)")")")"
fi
[[ -n "$SDK" && -d "$SDK/platforms" ]] || { echo 'Android SDK non trovato. Impostare ANDROID_HOME e riprovare.' >&2; exit 1; }
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVA_VERSION="$("$JAVA" -version 2>&1 | head -n1 || true)"
MAJOR="$(sed -nE 's/.*version "([0-9]+).*/\1/p' <<<"$JAVA_VERSION")"
if [[ ! "$MAJOR" =~ ^[0-9]+$ ]] || ((MAJOR<21)); then
  if [[ -d "$ROOT" ]]; then
    while IFS= read -r CANDIDATE; do
      CANDIDATE_VERSION="$("$CANDIDATE" -version 2>&1 | head -n1 || true)"
      CANDIDATE_MAJOR="$(sed -nE 's/.*version "([0-9]+).*/\1/p' <<<"$CANDIDATE_VERSION")"
      if [[ "$CANDIDATE_MAJOR" =~ ^[0-9]+$ ]] && ((CANDIDATE_MAJOR>=21)); then
        JAVA="$CANDIDATE"; JAVA_VERSION="$CANDIDATE_VERSION"; MAJOR="$CANDIDATE_MAJOR"
        export JAVA_HOME="$(dirname "$(dirname "$CANDIDATE")")"
        break
      fi
    done < <(find "$ROOT" -maxdepth 5 -type f -path '*/bin/java' -print)
  fi
fi
if [[ ! "$MAJOR" =~ ^[0-9]+$ ]] || ((MAJOR<21)); then
  echo 'Serve JDK 21 o successivo. Impostare JAVA_HOME al JDK presente in AndroidTools.' >&2; exit 1
fi
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
printf 'Progetto: %s\nSDK: %s\nJava: %s\n' "$PROJECT" "$SDK" "$JAVA_VERSION"
"$ADB" version | head -n1
printf '\nDispositivi ADB:\n'
"$ADB" devices -l
if [[ -x "$EMULATOR" ]]; then
  printf '\nEmulatori configurati:\n'; "$EMULATOR" -list-avds
else
  echo 'Emulatore non trovato in questo SDK: compilazione e test JVM restano disponibili.'
fi
if $CHECK; then exit 0; fi
if [[ -n "$SERIAL" && ! "$SERIAL" =~ ^emulator-[0-9]+$ ]]; then
  echo 'Installazione debug consentita soltanto su un emulatore.' >&2; exit 2
fi
# Build before starting an emulator; do not consume RAM while Gradle compiles.
export ANDROID_HOME="$SDK"
(
  cd "$PROJECT"
  bash ./gradlew :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug --console=plain
)
APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || { echo 'APK debug non prodotto.' >&2; exit 1; }
printf '\nAPK debug: %s\n' "$APK"
if [[ -n "$AVD" ]]; then
  [[ -x "$EMULATOR" ]] || { echo 'Emulatore non installato nello SDK selezionato.' >&2; exit 1; }
  "$EMULATOR" -list-avds | grep -Fx -- "$AVD" >/dev/null || { echo 'Nome AVD non trovato.' >&2; exit 1; }
  # Reserve a serial explicitly to avoid choosing another running emulator or a phone.
  [[ -n "$SERIAL" ]] || { echo 'Con --avd specificare anche --install-emulator emulator-PORTA.' >&2; exit 2; }
  PORT="${SERIAL#emulator-}"
  ((PORT>=5554 && PORT<=5682 && PORT%2==0)) || { echo 'Porta emulatore non valida.' >&2; exit 2; }
  if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo 'Seriale già occupato: usare --install-emulator senza --avd o scegliere un altro seriale.' >&2; exit 1
  fi
  "$EMULATOR" -avd "$AVD" -port "$PORT" -no-snapshot-save > /tmp/lyra-local-emulator.log 2>&1 &
  EMULATOR_PID=$!
  echo "Emulatore avviato (PID $EMULATOR_PID); log /tmp/lyra-local-emulator.log"
fi
if [[ -n "$SERIAL" ]]; then
  READY=false
  for ((attempt=0;attempt<90;attempt++)); do
    if [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then READY=true; break; fi
    sleep 2
  done
  $READY || { echo 'Avvio emulatore non completato entro 180 secondi.' >&2; exit 1; }
  "$ADB" -s "$SERIAL" install -r "$APK" || {
    echo 'Installazione fallita. Non disinstallo la versione esistente e non cancello dati.' >&2; exit 1;
  }
  "$ADB" -s "$SERIAL" shell am start -n app.lyra.music/.MainActivity
  echo 'App avviata. La riproduzione reale e la qualità musicale richiedono i test descritti nelle note 1.0.6.'
fi
