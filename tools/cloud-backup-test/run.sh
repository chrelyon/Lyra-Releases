#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${LYRA_GRADLE_RUNTIME:?Set to the unpacked Gradle directory}"
: "${LYRA_TEST_JSON_JAR:?Set to org.json jar}"
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
CP="$(printf '%s:' "$LYRA_GRADLE_RUNTIME"/lib/kotlin-stdlib-*.jar "$LYRA_GRADLE_RUNTIME"/lib/kotlinx-coroutines-core-jvm-*.jar "$LYRA_GRADLE_RUNTIME"/lib/annotations-*.jar)$LYRA_TEST_JSON_JAR"
SRC="$ROOT/app/src/main/java/app/lyra/music"
java -cp "$LYRA_GRADLE_RUNTIME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 21 -classpath "$CP" -d "$OUTPUT/classes" \
 "$ROOT"/tools/cloud-backup-test/*.kt \
 "$SRC/cloud/CloudProfileSync.kt" "$SRC/cloud/CloudProfileSyncSettings.kt" \
 "$SRC/cloud/DeviceBackup.kt" "$SRC/cloud/MusicSnapshotMerge.kt" "$SRC/cloud/UbuntuOnlyCutoverPolicy.kt" \
 "$SRC/genre/GenreDecisionPolicy.kt" "$SRC/core/model/Song.kt" \
 "$SRC/playback/PlaybackQueueUiState.kt" "$SRC/playback/PlaybackRepeatMode.kt" "$SRC/playback/ListeningHistoryStore.kt" "$SRC/playback/ListeningProfileStore.kt" "$SRC/personalization/ListeningEventStore.kt" \
 "$SRC/personalization/PersonalizationSettings.kt" "$SRC/diagnostics/DiagnosticsSettings.kt"
java -cp "$OUTPUT/classes:$CP" app.lyra.music.cloud.MainKt
