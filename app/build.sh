#!/usr/bin/env bash
# Manual build pipeline for PageLoupe (no Gradle in this VM).
# aapt2 -> kotlinc -> d8 -> sign. Source layout follows the standard Gradle
# layout (src/main/...) so the project can be converted to Gradle at handover.
set -e
cd "$(dirname "$0")"
APPDIR=$PWD
# No stale final artifact, removed before ANY verification can
# fail: a build that stops midway must never leave a signed APK
# under the release name (v0.41.0 review r8/r9). The pending
# name is cleaned on every exit; a successful build moves it to
# the final name first, which makes the trap a no-op.
rm -f build/pageloupe.apk build/pageloupe.apk.idsig \
  build/pageloupe-pending.apk build/pageloupe-pending.apk.idsig
# One EXIT trap for everything (registered once TMPD exists,
# further down): bash replaces same-signal traps instead of
# stacking them, so a second registration would silently drop
# the pending cleanup (v0.41.0 review r10).

# Toolchain resolution and the common checks live in
# toolchain-env.sh, shared with tests/run_tests.sh.
. ./toolchain-env.sh
BT=$SDK/build-tools/34.0.0
R8_JAR=${R8_JAR:-$TOOLS/r8/r8-8.7.18.jar}
# The build-only tools get the same fail-fast treatment, so a
# first build says WHICH variable to set instead of dying
# halfway down the pipeline.
need_exec "$BT/aapt2" "set ANDROID_HOME or PAGELOUPE_TOOLS"
need_exec "$BT/zipalign" "set ANDROID_HOME or PAGELOUPE_TOOLS"
need_exec "$BT/apksigner" "set ANDROID_HOME or PAGELOUPE_TOOLS"
need_exec "$JAVA_HOME/bin/javac" "set JAVA_HOME or PAGELOUPE_TOOLS"
need_exec "$JAVA_HOME/bin/jar" "set JAVA_HOME or PAGELOUPE_TOOLS"
need_exec "$JAVA_HOME/bin/keytool" "set JAVA_HOME or PAGELOUPE_TOOLS"
need_file "$STDLIB" "set KOTLINC_HOME or PAGELOUPE_TOOLS"
need_file "$R8_JAR" "set R8_JAR or PAGELOUPE_TOOLS"
[ "$toolchain_missing" -eq 0 ] || exit 1
echo "toolchain: JAVA_HOME=$JAVA_HOME SDK=$SDK KOTLINC_HOME=$KOTLINC_HOME R8_JAR=$R8_JAR"
MAIN=src/main
VERSION_CODE=${VERSION_CODE:-112}
VERSION_NAME=${VERSION_NAME:-0.43.0}

# Vendored libraries are verified against their recorded hashes
# before anything consumes them: a swapped or corrupted jar must
# fail the build, not ship. The extracted classes jars must also
# match the classes.jar inside their (hash-verified) AARs, so the
# chain from the published artifact to the dexed jar is closed.
# Provenance: libs/README.md.
(cd libs && sha256sum -c SHA256SUMS)
# Coverage runs the other way too: every artifact in libs/
# must be listed in SHA256SUMS, so a newly added jar cannot
# slip in unverified (v0.41.0 review r1). README.md is
# documentation, not an artifact, and stays out of both lists
# so editing it never breaks the build.
(cd libs && for f in *; do
  case "$f" in SHA256SUMS|README.md) continue ;; esac
  cut -c67- SHA256SUMS | grep -qxF "$f" || { echo "libs/$f is not in SHA256SUMS" >&2; exit 1; }
done)
# The Kotlin stdlib also ships in the dex but does not live in
# libs/, so the gate above cannot see it: pin it by hash here
# so the verification chain covers every jar that reaches the
# APK (run-3 hardening). The hash is of the Kotlin 2.0.21
# distribution's stdlib; updating the compiler means updating
# this pin in the same commit.
STDLIB_SHA256=f31cc53f105a7e48c093683bbd5437561d1233920513774b470805641bedbc09
echo "$STDLIB_SHA256  $STDLIB" | sha256sum -c - >/dev/null ||
  { echo "kotlin-stdlib.jar at $STDLIB does not match its pinned hash (Kotlin 2.0.21)" >&2; exit 1; }
# The R8 jar is in the same position (it is executed with
# java -cp and transforms every class that ships), so it is
# pinned the same way. The hash is of the R8 8.7.18 release
# artifact on Google Maven (com.android.tools:r8); updating
# R8 means updating this pin and the README in the same commit.
R8_SHA256=58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903
echo "$R8_SHA256  $R8_JAR" | sha256sum -c - >/dev/null ||
  { echo "r8 jar at $R8_JAR does not match its pinned hash (R8 8.7.18)" >&2; exit 1; }
TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"; rm -f build/pageloupe-pending.apk build/pageloupe-pending.apk.idsig' EXIT
# Every AAR in libs/ ships with its extracted classes jar beside it;
# verify each pair so no extracted jar can drift from its AAR.
for aar in libs/*.aar; do
  base=$(basename "$aar" .aar)
  # webkit-1.18.0.aar -> webkit-classes.jar and core-1.9.0.aar ->
  # core-classes.jar are the historical exceptions (the pairs
  # predate the uniform naming); everything else is
  # <name>-classes.jar.
  case "$base" in
    webkit-1.18.0) extracted=libs/webkit-classes.jar ;;
    core-1.9.0) extracted=libs/core-classes.jar ;;
    *) extracted="libs/$base-classes.jar" ;;
  esac
  unzip -p "$aar" classes.jar > "$TMPD/from-aar.jar"
  cmp "$TMPD/from-aar.jar" "$extracted"
done
echo "libs ok"

# The dexed library jars: the vendored androidx.webkit + androidx.core
# pair plus the ML Kit stack (v0.41.0, libs/README.md). The
# annotation jars (annotation, jspecify, firebase-annotations) are
# compile-classpath only and are NOT dexed.
LIB_JARS="libs/webkit-classes.jar libs/core-classes.jar \
libs/mlkit-translate-17.0.3-classes.jar libs/mlkit-language-id-17.0.6-classes.jar \
libs/mlkit-language-id-common-16.1.0-classes.jar libs/mlkit-common-18.11.0-classes.jar \
libs/play-services-base-18.5.0-classes.jar libs/play-services-basement-18.4.0-classes.jar \
libs/play-services-tasks-18.2.0-classes.jar libs/firebase-components-16.1.0-classes.jar \
libs/firebase-encoders-json-17.1.0-classes.jar libs/firebase-encoders-16.1.0.jar \
libs/datatransport-api-2.2.1-classes.jar libs/datatransport-runtime-2.2.6-classes.jar \
libs/datatransport-backend-cct-2.3.3-classes.jar libs/lifecycle-runtime-2.3.1-classes.jar \
libs/lifecycle-common-2.3.1.jar libs/arch-core-runtime-2.1.0-classes.jar \
libs/arch-core-common-2.1.0.jar libs/versionedparcelable-1.1.1-classes.jar \
libs/collection-1.0.0.jar libs/concurrent-futures-1.0.0.jar \
libs/listenablefuture-1.0.jar libs/javax-inject-1.jar \
libs/okhttp-3.12.13.jar libs/okio-1.17.6.jar"
COMPILE_ONLY_JARS="libs/annotation-1.8.1.jar libs/jspecify-1.0.0.jar libs/firebase-annotations-16.0.0.jar"
# Every jar in libs/ must be assigned to exactly one of the two
# lists: a jar added to libs/ (and to SHA256SUMS) but forgotten
# here would compile yet never reach the dex, the same shape of
# failure as the missing R classes (v0.41.0 review r9).
for j in libs/*.jar; do
  in_lib=0; in_compile_only=0
  case " $LIB_JARS " in *" $j "*) in_lib=1 ;; esac
  case " $COMPILE_ONLY_JARS " in *" $j "*) in_compile_only=1 ;; esac
  if [ $((in_lib + in_compile_only)) -ne 1 ]; then
    echo "jar in libs/ must be in exactly one of LIB_JARS / COMPILE_ONLY_JARS: $j" >&2; exit 1
  fi
done

rm -rf build/gen build/gen-lib build/classes-kt build/dex build/res.zip build/libres
mkdir -p build/gen build/gen-lib build/classes-kt build/dex build/libres

# 0) keystore (once)
if [ ! -f build/debug.keystore ]; then
  keytool -genkeypair -keystore build/debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=PageLoupe" >/dev/null 2>&1
  echo "keystore created"
fi

# 1) resources + manifest -> unsigned apk (also generates R.java)
# Library resources come out of the vendored AARs that carry a res/
# tree; they are compiled and linked together with the app's own,
# the manual equivalent of AGP's library resource merge.
# The AARs that carry resources, with the package their R class
# is generated for: one list drives both the res compile/link
# and the R generation below (v0.41.0 review r1).
LIB_RES_AARS="core-1.9.0|androidx.core
play-services-base-18.5.0|com.google.android.gms.base
play-services-basement-18.4.0|com.google.android.gms.common
lifecycle-runtime-2.3.1|androidx.lifecycle.runtime
mlkit-translate-17.0.3|com.google.mlkit.nl.translate"
# Coverage the other way: every AAR whose R.txt declares
# resources must be in the list above, or its resources are
# never linked and its R never generated (v0.41.0 review r8).
LIB_RES_FLAT=$(echo $LIB_RES_AARS)
for aar in libs/*.aar; do
  base=$(basename "$aar" .aar)
  if unzip -p "$aar" R.txt 2>/dev/null | grep -q .; then
    case " $LIB_RES_FLAT " in
      *" $base|"*) ;;
      *) echo "AAR with R.txt entries missing from LIB_RES_AARS: $base" >&2; exit 1 ;;
    esac
  fi
done
$BT/aapt2 compile --dir $MAIN/res -o build/res.zip
LIB_RES_ZIPS=""
mkdir -p "$TMPD/rtxt"
R_ARGS=""
for spec in $LIB_RES_AARS; do
  name=${spec%%|*}
  pkg=${spec##*|}
  mkdir -p "$TMPD/libres-$name"
  unzip -qo "libs/$name.aar" 'res/*' -d "$TMPD/libres-$name"
  $BT/aapt2 compile --dir "$TMPD/libres-$name/res" -o "build/libres/$name.zip"
  LIB_RES_ZIPS="$LIB_RES_ZIPS build/libres/$name.zip"
  unzip -p "libs/$name.aar" R.txt > "$TMPD/rtxt/$name.txt"
  R_ARGS="$R_ARGS $pkg $TMPD/rtxt/$name.txt"
done
$BT/aapt2 link -o build/app-unsigned.apk -I "$AJAR" --manifest $MAIN/AndroidManifest.xml \
  --java build/gen --output-text-symbols build/symbols.txt \
  --min-sdk-version 33 --target-sdk-version 34 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  build/res.zip $LIB_RES_ZIPS
# Library R classes from each AAR's R.txt against the link symbols
# (gen_library_r.py mirrors what AGP generates).
python3 gen_library_r.py build/symbols.txt build/gen-lib $R_ARGS
echo "aapt2 ok"

# 2) Kotlin (app sources + generated R)
# Hand-written .java under src/main would be picked up by
# neither kotlinc's '*.kt' find nor the R-only javac below and
# would vanish from the build silently; refuse it outright.
if [ -n "$(find $MAIN/java -name '*.java')" ]; then
  echo "hand-written .java sources are not part of this pipeline" >&2; exit 1
fi
CP="$AJAR:$(echo $LIB_JARS | tr ' ' ':'):$(echo $COMPILE_ONLY_JARS | tr ' ' ':')"
$KOTLINC -cp "$CP" -d build/classes-kt $(find $MAIN/java -name '*.kt') \
  build/gen/jp/kaleidpixel/pageloupe/R.java $(find build/gen-lib -name 'R.java')
echo "kotlinc ok"

# 2b) Bytecode for the generated R classes. kotlinc reads the
# .java sources for symbols but emits NO .class for them; the
# app's own R survives that because every reference to it is a
# compile-time constant inlined at the use site, but library
# bytecode (built by AGP, where library R fields are non-final)
# reads its R fields with real getstatic at runtime -- ML Kit's
# translator died with NoClassDefFoundError: R$xml the first
# time it ran (found by the v0.41.0 device E2E). Compile the R
# sources with javac into the same output tree, then cross-check
# every R field the vendored bytecode references against what
# was generated and emitted (check_r_refs.py): a missing type or
# field must fail the build, not the first use on a device.
"$JAVA_HOME/bin/javac" --release 8 -proc:none -encoding UTF-8 -cp "$AJAR" -d build/classes-kt \
  build/gen/jp/kaleidpixel/pageloupe/R.java $(find build/gen-lib -name 'R.java')
python3 check_r_refs.py build/classes-kt build/gen build/gen-lib -- $LIB_JARS
echo "javac R ok"

# 3) dex
jar cf build/app.jar -C build/classes-kt .
"$JAVA_HOME/bin/java" -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 33 --lib "$AJAR" --output build/dex \
  build/app.jar "$STDLIB" $LIB_JARS
echo "d8 ok"
# Close the last leg: every R type the vendored bytecode
# references must be DEFINED in the dex set that ships.
python3 check_r_refs.py --dex build/dex/classes*.dex -- $LIB_JARS

# 4) pack dex + native libs + bundled model, align, sign, verify
cp build/app-unsigned.apk build/app-with-dex.apk
(cd build/dex && zip -q ../app-with-dex.apk classes*.dex)
# ML Kit native engines and the bundled language-id model ship
# inside their AARs; only the device's ABI (arm64-v8a) is packed.
mkdir -p "$TMPD/apk/lib/arm64-v8a" "$TMPD/apk/assets"
unzip -p libs/mlkit-translate-17.0.3.aar jni/arm64-v8a/libtranslate_jni.so > "$TMPD/apk/lib/arm64-v8a/libtranslate_jni.so"
unzip -p libs/mlkit-language-id-17.0.6.aar jni/arm64-v8a/liblanguage_id_l2c_jni.so > "$TMPD/apk/lib/arm64-v8a/liblanguage_id_l2c_jni.so"
unzip -p libs/mlkit-language-id-17.0.6.aar assets/tflite_langid.tflite.jpg > "$TMPD/apk/assets/tflite_langid.tflite.jpg"
(cd "$TMPD/apk" && zip -q -0 "$APPDIR/build/app-with-dex.apk" lib/arm64-v8a/libtranslate_jni.so lib/arm64-v8a/liblanguage_id_l2c_jni.so assets/tflite_langid.tflite.jpg)
# extractNativeLibs=false: the .so files are stored uncompressed
# above and page-aligned here (-p), so the platform maps them in
# place instead of extracting a second copy at install time.
$BT/zipalign -f -p 4 build/app-with-dex.apk build/app-aligned.apk
# Sign under a pending name: the APK earns its final name only
# after every assertion below has passed, so a build that fails
# a check never leaves a signed artifact that looks shippable
# (v0.41.0 review r8).
$BT/apksigner sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/pageloupe-pending.apk build/app-aligned.apk
$BT/apksigner verify build/pageloupe-pending.apk
# The merged manifest is part of the security posture
# (v0.41.0 review r1):
# the ML Kit components must be present and the datatransport
# upload path must stay absent in the SHIPPED apk, not just in
# the source manifest.
MANIFEST_DUMP=$($BT/aapt2 dump xmltree build/pageloupe-pending.apk --file AndroidManifest.xml)
for required in MlKitInitProvider MlKitComponentDiscoveryService; do
  echo "$MANIFEST_DUMP" | grep -q "$required" || { echo "manifest assertion: $required missing" >&2; exit 1; }
done
for forbidden in TransportBackendDiscovery JobInfoSchedulerService AlarmManagerSchedulerBroadcastReceiver; do
  if echo "$MANIFEST_DUMP" | grep -q "$forbidden"; then
    echo "manifest assertion: $forbidden must not be merged" >&2; exit 1
  fi
done
# The component set as a whole is pinned to an explicit
# allowlist (run-3 hardening): the greps above name the
# failure modes that matter most, but a library update that
# merged some OTHER new component would pass them silently.
# Any change to the shipped set must update
# manifest-components.txt in the same commit.
SHIPPED_COMPONENTS=$(echo "$MANIFEST_DUMP" | awk '
  /^ *E: (activity|activity-alias|service|receiver|provider)( |$)/ { pending=1; next }
  /^ *E: / { pending=0 }
  pending && /:name\(0x01010003\)=/ { match($0, /"[^"]*"/); print substr($0, RSTART+1, RLENGTH-2); pending=0 }
' | sort)
EXPECTED_COMPONENTS=$(grep -v '^#' manifest-components.txt | grep -v '^$' | sort)
if [ "$SHIPPED_COMPONENTS" != "$EXPECTED_COMPONENTS" ]; then
  echo "manifest assertion: shipped components differ from manifest-components.txt" >&2
  diff <(echo "$EXPECTED_COMPONENTS") <(echo "$SHIPPED_COMPONENTS") >&2 || true
  exit 1
fi
echo "manifest assertions ok"
mv build/pageloupe-pending.apk build/pageloupe.apk
# The v4 signature sidecar follows its APK's name.
if [ -f build/pageloupe-pending.apk.idsig ]; then
  mv build/pageloupe-pending.apk.idsig build/pageloupe.apk.idsig
fi
$BT/aapt2 dump badging build/pageloupe.apk | head -4
ls -la build/pageloupe.apk
sha256sum build/pageloupe.apk
echo "BUILD_OK"
