#!/usr/bin/env bash
# Fetch the vendored dependencies from their canonical Maven
# repositories. The binaries themselves are not kept in the
# repository (anything a package manager can install is not
# committed); this script downloads each artifact once and
# verifies it against the SHA-256 recorded in libs/SHA256SUMS
# before anything consumes it. Provenance for every artifact:
# libs/README.md. Re-running is cheap: files whose hash already
# matches are left alone.
#
# The *-classes.jar files next to each AAR are derived, not
# downloaded: each is the AAR's classes.jar extracted unchanged
# (build.sh re-verifies that identity on every build).
set -e
cd "$(dirname "$0")"
GM=https://dl.google.com/dl/android/maven2
MC=https://repo1.maven.org/maven2
# Downloads land in a temp file INSIDE the target directory and
# are renamed only after their hash verifies, so a partial file
# never appears under the artifact's real name, on any
# filesystem. Leftovers from an interrupted run are cleaned
# both at start (a killed run's trap may not have fired) and
# on exit.
rm -f libs/.fetch-*.tmp ../tests/lib/.fetch-*.tmp
trap 'rm -f libs/.fetch-*.tmp ../tests/lib/.fetch-*.tmp' EXIT

# expected_hash <dir> <name>: the SHA-256 recorded for <name> in
# <dir>/SHA256SUMS, or empty when the file is not recorded.
expected_hash() {
  awk -v f="$2" '$2 == f {print $1}' "$1/SHA256SUMS"
}

# ensure_file <dir> <name> <url>: leave a hash-matching file in
# place; otherwise download to a temp file, verify, then move.
ensure_file() {
  local dir=$1 name=$2 url=$3 want got
  want=$(expected_hash "$dir" "$name")
  if [ -z "$want" ]; then
    echo "fetch_libs: $name is not recorded in $dir/SHA256SUMS" >&2
    exit 1
  fi
  if [ -f "$dir/$name" ]; then
    got=$(sha256sum "$dir/$name" | cut -d' ' -f1)
    if [ "$got" = "$want" ]; then
      return 0
    fi
    echo "fetch_libs: $name exists but its hash differs; refetching"
  fi
  curl -fL --proto '=https' --proto-redir '=https' --connect-timeout 30 \
    --max-time 600 --retry 3 -o "$dir/.fetch-$name.tmp" "$url"
  got=$(sha256sum "$dir/.fetch-$name.tmp" | cut -d' ' -f1)
  if [ "$got" != "$want" ]; then
    echo "fetch_libs: SHA-256 mismatch for $name" >&2
    echo "  expected $want" >&2
    echo "  got      $got" >&2
    exit 1
  fi
  mv "$dir/.fetch-$name.tmp" "$dir/$name"
  echo "fetched $name"
}

# derive_classes <aar-name> <out-name>: extract the AAR's
# classes.jar as libs/<out-name> unless it already matches.
derive_classes() {
  local aar=$1 out=$2 want got
  want=$(expected_hash libs "$out")
  if [ -z "$want" ]; then
    echo "fetch_libs: $out is not recorded in libs/SHA256SUMS" >&2
    exit 1
  fi
  if [ -f "libs/$out" ]; then
    got=$(sha256sum "libs/$out" | cut -d' ' -f1)
    if [ "$got" = "$want" ]; then
      return 0
    fi
  fi
  unzip -p "libs/$aar" classes.jar > "libs/.fetch-$out.tmp"
  got=$(sha256sum "libs/.fetch-$out.tmp" | cut -d' ' -f1)
  if [ "$got" != "$want" ]; then
    echo "fetch_libs: extracted $out does not match its recorded hash" >&2
    exit 1
  fi
  mv "libs/.fetch-$out.tmp" "libs/$out"
  echo "derived $out"
}

# --- app/libs: AARs (Google Maven) ---
ensure_file libs webkit-1.18.0.aar "$GM/androidx/webkit/webkit/1.18.0/webkit-1.18.0.aar"
ensure_file libs webkit-1.18.0.pom "$GM/androidx/webkit/webkit/1.18.0/webkit-1.18.0.pom"
ensure_file libs core-1.9.0.aar "$GM/androidx/core/core/1.9.0/core-1.9.0.aar"
ensure_file libs mlkit-translate-17.0.3.aar "$GM/com/google/mlkit/translate/17.0.3/translate-17.0.3.aar"
ensure_file libs mlkit-language-id-17.0.6.aar "$GM/com/google/mlkit/language-id/17.0.6/language-id-17.0.6.aar"
ensure_file libs mlkit-language-id-common-16.1.0.aar "$GM/com/google/mlkit/language-id-common/16.1.0/language-id-common-16.1.0.aar"
ensure_file libs mlkit-common-18.11.0.aar "$GM/com/google/mlkit/common/18.11.0/common-18.11.0.aar"
ensure_file libs play-services-base-18.5.0.aar "$GM/com/google/android/gms/play-services-base/18.5.0/play-services-base-18.5.0.aar"
ensure_file libs play-services-basement-18.4.0.aar "$GM/com/google/android/gms/play-services-basement/18.4.0/play-services-basement-18.4.0.aar"
ensure_file libs play-services-tasks-18.2.0.aar "$GM/com/google/android/gms/play-services-tasks/18.2.0/play-services-tasks-18.2.0.aar"
ensure_file libs firebase-components-16.1.0.aar "$GM/com/google/firebase/firebase-components/16.1.0/firebase-components-16.1.0.aar"
ensure_file libs firebase-encoders-json-17.1.0.aar "$GM/com/google/firebase/firebase-encoders-json/17.1.0/firebase-encoders-json-17.1.0.aar"
ensure_file libs datatransport-api-2.2.1.aar "$GM/com/google/android/datatransport/transport-api/2.2.1/transport-api-2.2.1.aar"
ensure_file libs datatransport-runtime-2.2.6.aar "$GM/com/google/android/datatransport/transport-runtime/2.2.6/transport-runtime-2.2.6.aar"
ensure_file libs datatransport-backend-cct-2.3.3.aar "$GM/com/google/android/datatransport/transport-backend-cct/2.3.3/transport-backend-cct-2.3.3.aar"
ensure_file libs lifecycle-runtime-2.3.1.aar "$GM/androidx/lifecycle/lifecycle-runtime/2.3.1/lifecycle-runtime-2.3.1.aar"
ensure_file libs arch-core-runtime-2.1.0.aar "$GM/androidx/arch/core/core-runtime/2.1.0/core-runtime-2.1.0.aar"
ensure_file libs versionedparcelable-1.1.1.aar "$GM/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.aar"

# --- app/libs: plain jars ---
ensure_file libs annotation-1.8.1.jar "$GM/androidx/annotation/annotation/1.8.1/annotation-1.8.1.jar"
ensure_file libs firebase-annotations-16.0.0.jar "$GM/com/google/firebase/firebase-annotations/16.0.0/firebase-annotations-16.0.0.jar"
ensure_file libs firebase-encoders-16.1.0.jar "$GM/com/google/firebase/firebase-encoders/16.1.0/firebase-encoders-16.1.0.jar"
ensure_file libs lifecycle-common-2.3.1.jar "$GM/androidx/lifecycle/lifecycle-common/2.3.1/lifecycle-common-2.3.1.jar"
ensure_file libs arch-core-common-2.1.0.jar "$GM/androidx/arch/core/core-common/2.1.0/core-common-2.1.0.jar"
ensure_file libs collection-1.0.0.jar "$GM/androidx/collection/collection/1.0.0/collection-1.0.0.jar"
ensure_file libs concurrent-futures-1.0.0.jar "$GM/androidx/concurrent/concurrent-futures/1.0.0/concurrent-futures-1.0.0.jar"
ensure_file libs jspecify-1.0.0.jar "$MC/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"
ensure_file libs listenablefuture-1.0.jar "$MC/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.jar"
ensure_file libs javax-inject-1.jar "$MC/javax/inject/javax.inject/1/javax.inject-1.jar"
ensure_file libs okhttp-3.12.13.jar "$MC/com/squareup/okhttp3/okhttp/3.12.13/okhttp-3.12.13.jar"
ensure_file libs okio-1.17.6.jar "$MC/com/squareup/okio/okio/1.17.6/okio-1.17.6.jar"

# --- app/libs: derived classes jars ---
# webkit and core keep their historical names; every other AAR's
# extraction is <name>-classes.jar (same rule as build.sh).
derive_classes webkit-1.18.0.aar webkit-classes.jar
derive_classes core-1.9.0.aar core-classes.jar
for aar in libs/*.aar; do
  base=$(basename "$aar" .aar)
  case "$base" in
    webkit-1.18.0|core-1.9.0) continue ;;
  esac
  derive_classes "$base.aar" "$base-classes.jar"
done

# --- tests/lib: the test-only JSON jar (never enters the APK;
# provenance in tests/lib/README.md) ---
ensure_file ../tests/lib json-20260814.jar "$MC/org/json/json/20260814/json-20260814.jar"

# Final gate, both directions, for both directories: every
# recorded file verifies, and no unrecorded file (dotfiles
# included) is present.
check_coverage() {
  (cd "$1" && find . -maxdepth 1 ! -type d | sed 's|^\./||' | while read -r f; do
    case "$f" in SHA256SUMS|README.md) continue ;; esac
    awk '{print $2}' SHA256SUMS | grep -qxF -- "$f" || { echo "$1/$f is not in SHA256SUMS" >&2; exit 1; }
  done)
}
(cd libs && sha256sum -c SHA256SUMS)
check_coverage libs
(cd ../tests/lib && sha256sum -c SHA256SUMS)
check_coverage ../tests/lib
echo "FETCH_OK"
