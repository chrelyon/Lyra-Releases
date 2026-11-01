#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${LYRA_GRADLE_RUNTIME:?Set to the unpacked Gradle directory}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
CLASSPATH="$(printf '%s:' "$LYRA_GRADLE_RUNTIME"/lib/kotlin-stdlib-*.jar "$LYRA_GRADLE_RUNTIME"/lib/kotlinx-coroutines-core-jvm-*.jar "$LYRA_GRADLE_RUNTIME"/lib/annotations-*.jar)"
"$JAVA" -cp "$LYRA_GRADLE_RUNTIME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 21 -classpath "$CLASSPATH" -d "$OUTPUT/classes" \
  "$ROOT/app/src/main/java/app/lyra/music/core/model/Song.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/youtube/repository/AdvancedPlaylistSearchRepository.kt" \
  "$ROOT/app/src/main/java/app/lyra/music/ui/discover/PlaylistSearchController.kt" \
  "$ROOT/tools/advanced-search-test/ModelsStub.kt" "$ROOT/tools/advanced-search-test/YouTubeStub.kt" "$ROOT/tools/advanced-search-test/Main.kt"
"$JAVA" -cp "$OUTPUT/classes:$CLASSPATH" app.lyra.music.advancedsearchtest.MainKt
