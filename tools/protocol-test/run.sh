#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${LYRA_GRADLE_RUNTIME:?Set to the unpacked Gradle directory (its lib directory contains Kotlin compiler)}"
: "${LYRA_TEST_JSON_JAR:?Set to org.json JSON jar for JVM tests}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
CLASSPATH="$(printf '%s:' "$LYRA_GRADLE_RUNTIME"/lib/kotlin-stdlib-*.jar "$LYRA_GRADLE_RUNTIME"/lib/kotlinx-coroutines-core-jvm-*.jar "$LYRA_GRADLE_RUNTIME"/lib/annotations-*.jar)$LYRA_TEST_JSON_JAR"
"$JAVA" -cp "$LYRA_GRADLE_RUNTIME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 21 -classpath "$CLASSPATH" -d "$OUTPUT/classes" \
  "$ROOT/app/src/main/java/app/lyra/music/core/model/Song.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/personalization/ServerQueuePolicy.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/personalization/ServerPlaylistClient.kt" \
  "$ROOT/tools/protocol-test/CloudStub.kt" "$ROOT/tools/protocol-test/ProfileStub.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/personalization/RequestedArtistPolicy.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/personalization/AutomaticVarietyPolicy.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/playback/AutomaticPlaylistManager.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/youtube/repository/AutomaticPlaylistRepository.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/genre/GenreDecisionPolicy.kt" \
  "$ROOT/tools/protocol-test/EventsStub.kt" "$ROOT/tools/protocol-test/ProtocolMain.kt" \
  "$ROOT/tools/protocol-test/ManagerMain.kt" "$ROOT/tools/protocol-test/YouTubeStub.kt" \
  "$ROOT/tools/protocol-test/ModelsStub.kt" "$ROOT/tools/protocol-test/RecommendationStub.kt" \
  "$ROOT/tools/protocol-test/VarietyStoreStub.kt" "$ROOT/tools/protocol-test/GenreStub.kt" "$ROOT/tools/protocol-test/LogStub.kt"
curl -fsS --max-time 5 http://127.0.0.1:8766/__reset >/dev/null
"$JAVA" -cp "$OUTPUT/classes:$CLASSPATH" app.lyra.music.protocoltest.ProtocolMainKt

"$JAVA" -cp "$OUTPUT/classes:$CLASSPATH" app.lyra.music.protocoltest.ManagerMainKt
