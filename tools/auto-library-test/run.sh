#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${LYRA_GRADLE_RUNTIME:?Set to Kotlin compiler runtime with lib/*.jar}"
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
CP="$(printf '%s:' "$LYRA_GRADLE_RUNTIME"/lib/kotlin-stdlib-*.jar "$LYRA_GRADLE_RUNTIME"/lib/kotlinx-coroutines-core-jvm-*.jar "$LYRA_GRADLE_RUNTIME"/lib/annotations-*.jar)"
python3 "$ROOT/tools/auto-library-test/search-routing.py" "$ROOT" "$OUTPUT"
java -cp "$LYRA_GRADLE_RUNTIME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 21 -classpath "$CP" -d "$OUTPUT/classes" \
 "$ROOT/app/src/main/java/app/lyra/music/playback/AutoLibraryPolicy.kt" "$ROOT/tools/auto-library-test/Main.kt" "$OUTPUT/SearchRouting.kt" "$OUTPUT/NoToken.kt"
java -cp "$OUTPUT/classes:$CP" app.lyra.music.playback.MainKt
java -cp "$OUTPUT/classes:$CP" app.lyra.music.autosearchtest.SearchRoutingKt
