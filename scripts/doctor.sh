#!/usr/bin/env bash
# Read-only environment check: says what this machine has, what it misses, and the exact command
# that fixes each gap. Installs nothing, downloads nothing, changes nothing - `make setup` does
# that. Exits non-zero when something the BUILD needs is missing; the optional sections never
# change the exit status.
#
# Run it as `make doctor` (which passes JAVA_HOME/ANDROID_HOME) or directly.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Everything below is derived from the build files, so this script cannot drift from them.
JDK="$(sed -n 's/.*JavaLanguageVersion.of(\([0-9]*\)).*/\1/p' app/build.gradle.kts)"
COMPILE_SDK="$(sed -n 's/.*compileSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts)"
GRADLE_VERSION="$(sed -n 's/.*gradle-\([0-9.]*\)-bin\.zip.*/\1/p' gradle/wrapper/gradle-wrapper.properties)"

JAVA_HOME="${JAVA_HOME:-}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

missing=0

# Colour only on a terminal, so a redirected run (a CI log, a paste into a bug report) stays plain.
if [ -t 1 ]; then G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; Z=$'\033[0m'; else G=""; Y=""; R=""; Z=""; fi

ok()      { printf '  %sOK%s       %-24s %s\n' "$G" "$Z" "$1" "$2"; }
note()    { printf '  --       %-24s %s\n' "$1" "$2"; }
absent()  { printf '  %sABSENT%s   %-24s %s\n' "$Y" "$Z" "$1" "$2"; [ -n "${3:-}" ] && printf '           %-24s fix: %s\n' "" "$3"; }
gone()    { printf '  %sMISSING%s  %-24s %s\n' "$R" "$Z" "$1" "$2"; [ -n "${3:-}" ] && printf '           %-24s fix: %s\n' "" "$3"; missing=$((missing + 1)); }
section() { printf '\n%s\n' "$1"; }

printf 'finador-android doctor - %s %s\n' "$(uname -s)" "$(uname -m)"
printf 'wanted by the build files: JDK %s, compileSdk %s, Gradle %s\n' "$JDK" "$COMPILE_SDK" "$GRADLE_VERSION"

# ---------------------------------------------------------------------------
section "BUILD AND TEST (required)"

if [ "$(uname -s)" = "Darwin" ]; then
  if command -v brew >/dev/null 2>&1; then
    ok "Homebrew" "$(brew --version 2>/dev/null | head -1)"
  else
    gone "Homebrew" "not on PATH" "see https://brew.sh (make setup needs it on macOS)"
  fi
else
  note "Homebrew" "not a macOS host: install the JDK and the SDK with your package manager"
fi

if [ -x "${JAVA_HOME}/bin/java" ]; then
  java_v="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"
  java_vendor="$("${JAVA_HOME}/bin/java" -version 2>&1 | sed -n '2s/ (build.*//p')"
  case "$java_v" in
    "${JDK}"|"${JDK}".*) ok "JDK ${JDK}" "$java_v ${java_vendor:-} ($JAVA_HOME)" ;;
    *) gone "JDK ${JDK}" "found $java_v at $JAVA_HOME, the build wants ${JDK}" \
            "brew install --cask temurin@${JDK}" ;;
  esac
else
  gone "JDK ${JDK}" "no java at ${JAVA_HOME:-<JAVA_HOME unset>}" \
       "brew install --cask temurin@${JDK}   # then: make doctor"
fi

if [ -d "${ANDROID_HOME}/cmdline-tools" ] || [ -d "${ANDROID_HOME}/platforms" ]; then
  ok "Android SDK" "$ANDROID_HOME"
else
  gone "Android SDK" "nothing at ${ANDROID_HOME:-<ANDROID_HOME unset>}" \
       "brew install --cask android-commandlinetools   # then: make setup"
fi

SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMANAGER" ]; then
  ok "SDK command-line tools" "$(cat "${ANDROID_HOME}/cmdline-tools/latest/source.properties" 2>/dev/null |
        sed -n 's/^Pkg.Revision=//p')"
else
  gone "SDK command-line tools" "no sdkmanager under ${ANDROID_HOME}/cmdline-tools/latest" \
       "brew install --cask android-commandlinetools"
fi

if [ -f "${ANDROID_HOME}/licenses/android-sdk-license" ]; then
  ok "SDK licences" "accepted ($(ls "${ANDROID_HOME}/licenses" 2>/dev/null | wc -l | tr -d ' ') files)"
else
  gone "SDK licences" "not accepted, so no SDK package can be installed" \
       "yes | sdkmanager --licenses   # or just: make setup"
fi

platform="$(ls -d "${ANDROID_HOME}"/platforms/android-"${COMPILE_SDK}"* 2>/dev/null | tail -1)"
if [ -n "$platform" ]; then
  ok "Platform android-${COMPILE_SDK}" "$(basename "$platform")"
else
  gone "Platform android-${COMPILE_SDK}" "compileSdk ${COMPILE_SDK} is not installed" \
       "make setup   # sdkmanager 'platforms;android-${COMPILE_SDK}'"
fi

buildtools="$(ls "${ANDROID_HOME}"/build-tools 2>/dev/null | sort -V | tr '\n' ' ')"
if [ -n "$buildtools" ]; then
  ok "Build-tools" "$buildtools"
else
  # Not fatal: with the licences accepted, the Android Gradle Plugin installs the revision it
  # wants on the first build. It is only slow and surprising the first time.
  absent "Build-tools" "none installed (the build will download one on first run)" \
         "make setup"
fi

if [ -f local.properties ]; then
  ok "local.properties" "$(sed -n 's/^sdk.dir=//p' local.properties)"
else
  # The Makefile exports ANDROID_HOME itself, so this only matters to Android Studio and to a
  # bare ./gradlew run from a shell with nothing exported.
  absent "local.properties" "absent (fine for make; Android Studio wants it)" \
         "make setup"
fi

# ---------------------------------------------------------------------------
section "DEVICE AND EMULATOR (optional: make install / make run / make emulator)"

if [ -x "${ANDROID_HOME}/platform-tools/adb" ]; then
  ok "adb" "$("${ANDROID_HOME}/platform-tools/adb" --version 2>/dev/null | head -1)"
else
  absent "adb" "platform-tools not installed" "make setup   # sdkmanager 'platform-tools'"
fi

if [ -x "${ANDROID_HOME}/emulator/emulator" ]; then
  ok "emulator" "$("${ANDROID_HOME}/emulator/emulator" -version 2>/dev/null | head -1 | cut -c1-60)"
else
  absent "emulator" "not installed" "make setup-emulator"
fi

images="$(ls -d "${ANDROID_HOME}"/system-images/android-* 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ')"
if [ -n "$images" ]; then
  ok "system images" "$images"
else
  absent "system images" "none" "make setup-emulator   # ~1.5 GB"
fi

avds="$(ls "${HOME}"/.android/avd/*.ini 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.ini$//' | tr '\n' ' ')"
if [ -n "$avds" ]; then
  ok "AVDs" "$avds"
else
  absent "AVDs" "none created" "make setup-emulator"
fi

# ---------------------------------------------------------------------------
section "CROSS-IMPLEMENTATION GATE (optional: make crossimpl)"

if command -v go >/dev/null 2>&1; then
  ok "Go toolchain" "$(go version | awk '{print $3}')"
else
  absent "Go toolchain" "not on PATH" "brew install go"
fi

if [ -d "${ROOT}/../finador" ]; then
  ok "finador checkout" "$(cd "${ROOT}/../finador" && pwd)"
else
  absent "finador checkout" "no sibling ../finador" "git clone <the finador repo> ../finador"
fi

# ---------------------------------------------------------------------------
section "RELEASE (maintainer only: make gh-release)"

# Never prints a secret: only whether the four signing properties are reachable.
if [ -n "${FINADOR_STORE_FILE:-}" ]; then
  ok "release keystore" "configured (environment)"
elif grep -qE '^[[:space:]]*FINADOR_STORE_FILE[[:space:]]*=' "${HOME}/.gradle/gradle.properties" 2>/dev/null; then
  ok "release keystore" "configured (~/.gradle/gradle.properties)"
else
  absent "release keystore" "absent: a release build would be DEBUG-signed" "make check-signing"
fi

if command -v gh >/dev/null 2>&1; then
  ok "gh CLI" "$(gh --version 2>/dev/null | head -1)"
else
  absent "gh CLI" "not on PATH" "brew install gh"
fi

apksigner="$(ls -d "${ANDROID_HOME}"/build-tools/* 2>/dev/null | sort -V | tail -1)/apksigner"
if [ -x "$apksigner" ]; then
  ok "apksigner" "$apksigner"
else
  absent "apksigner" "comes with the build-tools" "make setup"
fi

# ---------------------------------------------------------------------------
printf '\n'
if [ "$missing" -eq 0 ]; then
  printf 'Everything the build needs is here. Next: make test\n'
  exit 0
fi
printf '%d required item(s) missing. On macOS, `make setup` installs all of them.\n' "$missing"
exit 1
