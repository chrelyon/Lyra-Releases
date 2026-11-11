#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${LYRA_GRADLE_RUNTIME:?Set to the unpacked Gradle directory}"
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
CP="$(printf '%s:' "$LYRA_GRADLE_RUNTIME"/lib/kotlin-stdlib-*.jar "$LYRA_GRADLE_RUNTIME"/lib/annotations-*.jar)"
java -cp "$LYRA_GRADLE_RUNTIME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 21 -classpath "$CP" -d "$OUTPUT/classes" \
 "$ROOT/app/src/main/java/app/lyra/music/access/TokenActivationLink.kt" "$ROOT/tools/token-link-test/Main.kt"
java -cp "$OUTPUT/classes:$CP" app.lyra.music.tokenlinktest.MainKt
