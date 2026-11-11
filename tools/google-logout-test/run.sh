#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${LYRA_GRADLE_RUNTIME:?Set to the unpacked Gradle directory}"
: "${LYRA_TEST_JSON_JAR:?Set to the org.json jar}"
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
CP="$(printf '%s:' "$LYRA_GRADLE_RUNTIME"/lib/kotlin-stdlib-*.jar "$LYRA_GRADLE_RUNTIME"/lib/kotlinx-coroutines-core-jvm-*.jar "$LYRA_GRADLE_RUNTIME"/lib/annotations-*.jar)$LYRA_TEST_JSON_JAR"
java -cp "$LYRA_GRADLE_RUNTIME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 21 -classpath "$CP" -d "$OUTPUT/classes" \
 "$ROOT/app/src/main/java/app/lyra/music/youtube/auth/YouTubeAuthorizationManager.kt" \
 "$ROOT/tools/local-playback-test/AndroidStub.kt" "$ROOT/tools/local-playback-test/LogStub.kt" \
 "$ROOT"/tools/google-logout-test/*.kt
java -cp "$OUTPUT/classes:$CP" app.lyra.music.googlelogouttest.MainKt
