#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${LYRA_GRADLE_RUNTIME:?Set to the unpacked Gradle directory}"
: "${LYRA_TEST_JSON_JAR:?Set to the org.json jar}"
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
CP="$(printf '%s:' "$LYRA_GRADLE_RUNTIME"/lib/kotlin-stdlib-*.jar "$LYRA_GRADLE_RUNTIME"/lib/kotlinx-coroutines-core-jvm-*.jar "$LYRA_GRADLE_RUNTIME"/lib/annotations-*.jar)$LYRA_TEST_JSON_JAR"
SRC="$ROOT/app/src/main/java/app/lyra/music"
STUB="$ROOT/tools/protocol-test"
java -cp "$LYRA_GRADLE_RUNTIME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 21 -classpath "$CP" -d "$OUTPUT/classes" \
 "$SRC/youtube/repository/DiscoverySettings.kt" "$SRC/personalization/RegionPreferencePolicy.kt" "$ROOT/tools/local-playback-test/DiscoveryCacheStub.kt" \
 "$SRC/core/model/Song.kt" "$SRC/personalization/PersonalizationSettings.kt" \
 "$SRC/personalization/ServerQueuePolicy.kt" "$SRC/personalization/ServerPlaylistClient.kt" \
 "$SRC/personalization/RequestedArtistPolicy.kt" "$SRC/personalization/AutomaticVarietyPolicy.kt" \
 "$SRC/diagnostics/DiagnosticsSettings.kt" "$SRC/playback/AutomaticPlaylistManager.kt" \
 "$SRC/youtube/repository/AutomaticPlaylistRepository.kt" \
 "$STUB/CloudStub.kt" "$STUB/ProfileStub.kt" "$STUB/EventsStub.kt" \
 "$STUB/VarietyStoreStub.kt" "$STUB/ModelsStub.kt" "$STUB/YouTubeStub.kt" \
 "$ROOT/tools/local-playback-test/AndroidStub.kt" "$ROOT/tools/local-playback-test/AccessStub.kt" \
 "$ROOT/tools/local-playback-test/RecommendationStub.kt" "$ROOT/tools/local-playback-test/LogStub.kt" \
 "$ROOT/tools/local-playback-test/Main.kt"
java -cp "$OUTPUT/classes:$CP" app.lyra.music.localtest.MainKt
