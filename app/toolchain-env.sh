# Toolchain resolution shared by app/build.sh and
# tests/run_tests.sh. This file is SOURCED, not executed.
#
# Resolution order: an explicit PAGELOUPE_TOOLS pins the
# whole layout under that root (jdk/, sdk/, kotlinc/, r8/)
# and beats the ambient JAVA_HOME / ANDROID_HOME. Without
# it, the ambient variables win over the default root.
# KOTLINC_HOME overrides the Kotlin compiler alone.
# KOTLIN_HOME is deliberately NOT read: SDK managers and
# IDEs set it for their own Kotlin, which is almost never
# the pinned 2.0.21 this build requires (the stdlib hash
# check in build.sh is the backstop).
#
# Required versions are listed in the top-level README.md.
# On any problem this file prints which variable to set and
# fails the sourcing script.
TOOLS=${PAGELOUPE_TOOLS:-$HOME/workspace/tools}
if [ -n "${PAGELOUPE_TOOLS:-}" ]; then
  DEF_JDK=$TOOLS/jdk
  DEF_SDK=$TOOLS/sdk
else
  DEF_JDK=${JAVA_HOME:-$TOOLS/jdk}
  DEF_SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$TOOLS/sdk}}
fi
export JAVA_HOME=$DEF_JDK
export PATH=$JAVA_HOME/bin:$PATH
SDK=$DEF_SDK
KOTLINC_HOME=${KOTLINC_HOME:-$TOOLS/kotlinc}
KOTLINC=$KOTLINC_HOME/bin/kotlinc
STDLIB=$KOTLINC_HOME/lib/kotlin-stdlib.jar
AJAR=$SDK/platforms/android-34/android.jar

toolchain_missing=0
need_exec() { [ -x "$1" ] || { echo "toolchain: not executable: $1 -- $2" >&2; toolchain_missing=1; }; }
need_file() { [ -f "$1" ] || { echo "toolchain: not found: $1 -- $2" >&2; toolchain_missing=1; }; }
need_exec "$JAVA_HOME/bin/java" "set JAVA_HOME or PAGELOUPE_TOOLS"
need_exec "$KOTLINC" "set KOTLINC_HOME or PAGELOUPE_TOOLS"
need_file "$AJAR" "set ANDROID_HOME or PAGELOUPE_TOOLS"
if [ "$toolchain_missing" -ne 0 ]; then
  return 1 2>/dev/null || exit 1
fi
JAVA_MAJOR=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)
if [ "$JAVA_MAJOR" != "17" ]; then
  echo "toolchain: JDK 17 is required (JAVA_HOME=$JAVA_HOME)" >&2
  return 1 2>/dev/null || exit 1
fi
