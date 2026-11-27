#!/usr/bin/env bash
# JVM unit tests for the pure-JVM parts of the app (WsMessageTap,
# UrlInput, TargetSearch, DebugLog's dropped-call counter,
# ConsoleLogRecorder's text exports, CDP ingestion and console
# JSON export, the recorder's internal-Document exclusion, the
# start page HTML's no-subresource invariant, the profile
# policies, the
# DevTools session-state holder, the atomic file writer behind
# the requests export, the queued single writer behind the
# history and tab-session files,
# and
# the profile-name collision checks in ProfileNamesTest, and
# the MediaStore selection behind the exported logs, and
# the DevTools panel's height arithmetic, and
# the DevTools proxy's relay loop ordering, and the browser menu's structure,
# and the footer share text).
# The app sources compile against android.jar for their Android
# imports, but the tests only exercise classes that never call
# into Android at runtime. RecorderTextTest runs against the
# android.jar stubs: the methods it covers never call org.json.
# RecorderJsonTest covers the recorder's org.json side (CDP
# ingestion and the console JSON export): it runs with the
# test-only JSON-java jar (tests/lib, pinned by SHA-256 and
# verified below) ahead of android.jar on the RUNTIME classpath
# only — the jar is never on the compile classpath and never
# enters the app. See tests/lib/README.md for the scope limits.
set -e
cd "$(dirname "$0")/.."
# Verify the pinned test-only jar before anything runs (see
# tests/lib/README.md): SHA256SUMS must list exactly the jar
# the RecorderJson run line below uses, and its hash must
# match. Any failure aborts the whole run.
JSON_JAR=tests/lib/json-20260814.jar
jar_dir=$(dirname "$JSON_JAR")
jar_name=$(basename "$JSON_JAR")
jar_ok=1
[ "$(grep -c . "$jar_dir/SHA256SUMS")" -eq 1 ] || jar_ok=0
awk -v n="$jar_name" 'NF != 2 || $2 != n { bad = 1 } END { exit bad }' "$jar_dir/SHA256SUMS" || jar_ok=0
[ "$(awk '{ print $1 }' "$jar_dir/SHA256SUMS")" = "$(sha256sum "$JSON_JAR" | awk '{ print $1 }')" ] || jar_ok=0
if [ "$jar_ok" -ne 1 ]; then
  echo "tests/lib: jar verification failed - see tests/lib/README.md" >&2
  exit 1
fi
# Toolchain resolution and checks are shared with
# app/build.sh via app/toolchain-env.sh.
. "$(dirname "$0")/../app/toolchain-env.sh"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -cp "$AJAR:app/libs/webkit-classes.jar" -include-runtime -d "$OUT/tests.jar" \
  app/src/main/java/jp/kaleidpixel/pageloupe/DevToolsProxy.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/DebugLog.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/Settings.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/UrlInput.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/TargetSearch.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ConsoleLogRecorder.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/StartPageHtml.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/SecretProfilePolicy.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ProfileSupport.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/DevToolsProfilePolicy.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/PageFeaturePolicy.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/PendingSlot.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/DevToolsSessionState.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/AtomicFileWrite.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/QueuedFileWriter.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/HistoryStore.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/TabSessionStore.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ThumbnailStore.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/MenuSpec.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ExportedLogs.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ExportSelection.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/PanelHeight.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/PopupProbeRegistry.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/DownloadInitiatorTracker.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/CommitUrl.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/LoadStartLatch.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ErrorPageTracker.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/NewDocumentLatch.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/RecordedLogs.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ExportPolicy.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ExportMove.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/ShareText.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/TitleAttribution.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/HistoryTitle.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/TranslatePlan.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/BookmarkStore.kt \
  app/src/main/java/jp/kaleidpixel/pageloupe/FaviconStore.kt \
  tests/WsMessageTapTest.kt \
  tests/UrlInputTest.kt \
  tests/TargetSearchTest.kt \
  tests/DebugLogCounterTest.kt \
  tests/RecorderTextTest.kt \
  tests/SecretProfilePolicyTest.kt \
  tests/ProfileNamesTest.kt \
  tests/DevToolsProfilePolicyTest.kt \
  tests/PageFeaturePolicyTest.kt \
  tests/PendingSlotTest.kt \
  tests/DevToolsSessionStateTest.kt \
  tests/AtomicFileWriteTest.kt \
  tests/RelayChunksTest.kt \
  tests/RecorderJsonTest.kt \
  tests/RecorderRequestsJsonTest.kt \
  tests/StartPageHtmlTest.kt \
  tests/ConsoleLogRecorderGateTest.kt \
  tests/HistoryStoreTest.kt \
  tests/TabSessionStoreTest.kt \
  tests/ThumbnailStoreTest.kt \
  tests/QueuedFileWriterTest.kt \
  tests/MenuSpecTest.kt \
  tests/ExportSelectionTest.kt \
  tests/PanelHeightTest.kt \
  tests/PopupProbeRegistryTest.kt \
  tests/DownloadInitiatorTrackerTest.kt \
  tests/CommitUrlTest.kt \
  tests/LoadStartLatchTest.kt \
  tests/ErrorPageTrackerTest.kt \
  tests/NewDocumentLatchTest.kt \
  tests/RecordedLogsPolicyTest.kt \
  tests/ExportPolicyTest.kt \
  tests/ExportMoveTest.kt \
  tests/ShareTextTest.kt \
  tests/TitleAttributionTest.kt \
  tests/HistoryTitleTest.kt \
  tests/TranslatePlanTest.kt \
  tests/BookmarkStoreTest.kt \
  tests/FaviconStoreTest.kt \
  tests/DevToolsProxyPolicyTest.kt
# Run every suite even if one fails, so a single run shows all
# results; the exit code still reports any failure.
set +e
status=0
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" WsMessageTapTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.UrlInputTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.TargetSearchTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.DebugLogCounterTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar:$AJAR" tests.RecorderTextTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.SecretProfilePolicyTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.ProfileNamesTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.DevToolsProfilePolicyTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.PageFeaturePolicyTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.PendingSlotTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.DevToolsSessionStateTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.AtomicFileWriteTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.RelayChunksTestKt || status=1
"$JAVA_HOME/bin/java" -Dpageloupe.test.jsonJar="$jar_name" -cp "$JSON_JAR:$OUT/tests.jar:$AJAR" tests.RecorderJsonTestKt || status=1
"$JAVA_HOME/bin/java" -Dpageloupe.test.jsonJar="$jar_name" -cp "$JSON_JAR:$OUT/tests.jar:$AJAR" tests.RecorderRequestsJsonTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.StartPageHtmlTestKt || status=1
"$JAVA_HOME/bin/java" -Dpageloupe.test.jsonJar="$jar_name" -cp "$JSON_JAR:$OUT/tests.jar:$AJAR" tests.HistoryStoreTestKt || status=1
"$JAVA_HOME/bin/java" -Dpageloupe.test.jsonJar="$jar_name" -cp "$JSON_JAR:$OUT/tests.jar:$AJAR" tests.TabSessionStoreTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.QueuedFileWriterTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar:$AJAR" tests.ThumbnailStoreTestKt || status=1
"$JAVA_HOME/bin/java" -Dpageloupe.test.jsonJar="$jar_name" -cp "$JSON_JAR:$OUT/tests.jar:$AJAR" tests.ConsoleLogRecorderGateTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.MenuSpecTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.ExportSelectionTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.PanelHeightTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.PopupProbeRegistryTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.DownloadInitiatorTrackerTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.CommitUrlTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.LoadStartLatchTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.ErrorPageTrackerTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.NewDocumentLatchTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.RecordedLogsPolicyTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.ExportPolicyTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.ExportMoveTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.ShareTextTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.TitleAttributionTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.HistoryTitleTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.TranslatePlanTestKt || status=1
"$JAVA_HOME/bin/java" -Dpageloupe.test.jsonJar="$jar_name" -cp "$JSON_JAR:$OUT/tests.jar:$AJAR" tests.BookmarkStoreTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar:$AJAR" tests.FaviconStoreTestKt || status=1
"$JAVA_HOME/bin/java" -cp "$OUT/tests.jar" tests.DevToolsProxyPolicyTestKt || status=1
exit $status
