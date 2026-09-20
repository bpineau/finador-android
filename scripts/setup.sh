#!/usr/bin/env bash
# One-command dev environment: installs whatever `make doctor` reports missing, and nothing else.
#
# Idempotent by construction - every step checks first, so re-running is a no-op that prints what
# is already there. It installs a JDK and the Android SDK (Homebrew on macOS, your package manager
# elsewhere), accepts the SDK licences non-interactively, installs the SDK packages the build
# files ask for, and writes local.properties if it is absent.
#
# It NEVER touches a signing key: the release keystore and its passwords live outside this repo
# and are created by hand once (see README, "Release").
#
#   scripts/setup.sh              # build and test
#   scripts/setup.sh --emulator   # and the emulator, its system image and an AVD (~1.5 GB)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

WANT_EMULATOR=0
[ "${1:-}" = "--emulator" ] && WANT_EMULATOR=1

# Read from the build files, so this script cannot ask for a version the build does not want.
JDK="$(sed -n 's/.*JavaLanguageVersion.of(\([0-9]*\)).*/\1/p' app/build.gradle.kts)"
COMPILE_SDK="$(sed -n 's/.*compileSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts)"

say()  { printf '\n==> %s\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
# 1. The JDK
# ---------------------------------------------------------------------------
say "JDK ${JDK}"
if [ -x "${JAVA_HOME:-}/bin/java" ] &&
   "${JAVA_HOME}/bin/java" -version 2>&1 | head -1 | grep -q "\"${JDK}[.\"]"; then
  echo "already here: $JAVA_HOME"
elif JH="$(/usr/libexec/java_home -v "$JDK" 2>/dev/null)" && [ -n "$JH" ]; then
  JAVA_HOME="$JH"; echo "found: $JAVA_HOME"
elif [ "$(uname -s)" = "Darwin" ]; then
  have brew || { echo "Homebrew is required on macOS: see https://brew.sh"; exit 1; }
  echo "installing Temurin ${JDK} (brew cask)"
  brew install --cask "temurin@${JDK}"
  JAVA_HOME="$(/usr/libexec/java_home -v "$JDK")"
else
  cat <<EOF
No JDK ${JDK} found, and this is not macOS, so nothing is installed for you. Install a JDK ${JDK}
with your package manager - for example:

  Debian/Ubuntu : sudo apt install temurin-${JDK}-jdk   (Adoptium apt repo) or openjdk-${JDK}-jdk
  Fedora        : sudo dnf install java-${JDK}-openjdk-devel
  Arch          : sudo pacman -S jdk${JDK}-openjdk

then re-run:  JAVA_HOME=/path/to/jdk-${JDK} make setup
EOF
  exit 1
fi
export JAVA_HOME

# ---------------------------------------------------------------------------
# 2. The Android SDK command-line tools
# ---------------------------------------------------------------------------
say "Android SDK"
if [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [ -z "${ANDROID_HOME:-}" ] && have brew; then
  ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
fi
: "${ANDROID_HOME:=${HOME}/Android/Sdk}"

SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMANAGER" ]; then
  echo "already here: $ANDROID_HOME"
elif [ "$(uname -s)" = "Darwin" ]; then
  have brew || { echo "Homebrew is required on macOS: see https://brew.sh"; exit 1; }
  echo "installing the SDK command-line tools (brew cask)"
  brew install --cask android-commandlinetools
  ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
  SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
else
  cat <<EOF
No SDK command-line tools under ${ANDROID_HOME}, and this is not macOS. Download the Linux
"command line tools only" archive from https://developer.android.com/studio#command-line-tools-only
and unpack it so that this path exists:

  \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager

then re-run:  ANDROID_HOME=/path/to/sdk make setup
EOF
  exit 1
fi
export ANDROID_HOME
[ -x "$SDKMANAGER" ] || { echo "ERROR: still no sdkmanager at $SDKMANAGER"; exit 1; }

# One wrapper for every package install, so the day `sdkmanager` is retired in favour of the
# `android` CLI that now ships beside it (cmdline-tools 22 already prints a deprecation banner on
# every call), only this function changes. `sdkmanager` is kept for now because it is the one of
# the two with a documented non-interactive licence flow, which is what makes this script
# unattended. Silent on success, and on failure it prints what the tool said, minus that banner.
sdk_install() {
  local out
  if out="$("$SDKMANAGER" --sdk_root="$ANDROID_HOME" "$@" 2>&1)"; then return 0; fi
  printf '%s\n' "$out" | grep -vE "SDK Manager CLI tool|'android' binary|Android CLI and how" >&2
  return 1
}

# ---------------------------------------------------------------------------
# 3. Licences - nothing can be installed before they are accepted
# ---------------------------------------------------------------------------
say "SDK licences"
if [ -f "${ANDROID_HOME}/licenses/android-sdk-license" ]; then
  echo "already accepted"
else
  echo "accepting (yes | sdkmanager --licenses)"
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  [ -f "${ANDROID_HOME}/licenses/android-sdk-license" ] ||
    { echo "ERROR: the licences were not accepted"; exit 1; }
fi

# ---------------------------------------------------------------------------
# 4. The SDK packages the build files ask for
# ---------------------------------------------------------------------------
say "SDK packages"
if [ -x "${ANDROID_HOME}/platform-tools/adb" ]; then
  echo "platform-tools: already here"
else
  echo "platform-tools: installing"
  sdk_install "platform-tools"
fi

# Since Android 17 a platform package carries a minor version (`android-37.0`), where API 36 and
# below are plain (`android-36`). Try the new spelling first and fall back, rather than encoding a
# naming rule that will change again.
if ls -d "${ANDROID_HOME}"/platforms/android-"${COMPILE_SDK}"* >/dev/null 2>&1; then
  echo "platforms;android-${COMPILE_SDK}: already here"
else
  echo "platforms;android-${COMPILE_SDK}: installing"
  sdk_install "platforms;android-${COMPILE_SDK}.0" ||
    sdk_install "platforms;android-${COMPILE_SDK}" ||
    { echo "ERROR: no platform package for compileSdk ${COMPILE_SDK}"; exit 1; }
fi

# The Android Gradle Plugin downloads the exact build-tools revision it wants on the first build
# (the licences are accepted by now), so this only saves that surprise wait, and gives `apksigner`
# to the release targets.
if ls "${ANDROID_HOME}"/build-tools >/dev/null 2>&1 && [ -n "$(ls "${ANDROID_HOME}"/build-tools)" ]; then
  echo "build-tools: already here ($(ls "${ANDROID_HOME}"/build-tools | sort -V | tr '\n' ' '))"
else
  echo "build-tools;${COMPILE_SDK}.0.0: installing"
  sdk_install "build-tools;${COMPILE_SDK}.0.0" || echo "note: left to the first build"
fi

# ---------------------------------------------------------------------------
# 5. The emulator, on request only (it is the big download)
# ---------------------------------------------------------------------------
if [ "$WANT_EMULATOR" = "1" ]; then
  say "Emulator"
  case "$(uname -m)" in arm64|aarch64) ABI="arm64-v8a" ;; *) ABI="x86_64" ;; esac
  [ -x "${ANDROID_HOME}/emulator/emulator" ] && echo "emulator: already here" ||
    { echo "emulator: installing"; sdk_install "emulator"; }

  IMAGE="system-images;android-${COMPILE_SDK}.0;google_apis;${ABI}"
  if [ -d "${ANDROID_HOME}/system-images/android-${COMPILE_SDK}.0" ]; then
    echo "$IMAGE: already here"
  else
    echo "$IMAGE: installing (~1.5 GB)"
    sdk_install "$IMAGE" || {
      IMAGE="system-images;android-${COMPILE_SDK};google_apis;${ABI}"
      echo "retrying as $IMAGE"; sdk_install "$IMAGE"
    }
  fi

  AVD="test${COMPILE_SDK}"
  if [ -f "${HOME}/.android/avd/${AVD}.ini" ]; then
    echo "AVD ${AVD}: already here"
  else
    echo "AVD ${AVD}: creating"
    echo no | "${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager" create avd \
      -n "$AVD" -k "$IMAGE" >/dev/null
  fi
  echo "boot it with: make emulator AVD=${AVD}"
fi

# ---------------------------------------------------------------------------
# 6. local.properties - only for tools that do not read ANDROID_HOME (Android Studio)
# ---------------------------------------------------------------------------
say "local.properties"
if [ -f local.properties ]; then
  echo "already here: $(cat local.properties)"
else
  echo "sdk.dir=${ANDROID_HOME}" > local.properties
  echo "written: sdk.dir=${ANDROID_HOME}   (git-ignored, machine-local)"
fi

# ---------------------------------------------------------------------------
say "Result"
JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_HOME" "${ROOT}/scripts/doctor.sh" || exit 1
cat <<EOF

Environment ready. The next command is:

  make test     # 305 unit tests on the host JVM, no device needed

If your shell does not export JAVA_HOME/ANDROID_HOME, nothing is broken: every make target
exports these itself.
  JAVA_HOME=$JAVA_HOME
  ANDROID_HOME=$ANDROID_HOME
EOF
