#!/usr/bin/env bash
# Shared discovery for make, setup and doctor. Explicit environment overrides always win.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK="$(sed -n 's/.*JavaLanguageVersion.of(\([0-9]*\)).*/\1/p' "$ROOT/app/build.gradle.kts")"

case "${1:-}" in
  java)
    if [ -n "${JAVA_HOME:-}" ]; then printf '%s\n' "$JAVA_HOME"; exit 0; fi
    if [ "$(uname -s)" = Darwin ]; then
      # Without -F, java_home returns the default JVM even when no version matches.
      if java_home="$(/usr/libexec/java_home -F -v "$JDK" 2>/dev/null)" && [ -x "$java_home/bin/javac" ]; then
        printf '%s\n' "$java_home"; exit 0
      fi
    fi
    # Asking java itself follows package-manager symlinks on Linux as well as macOS.
    if command -v java >/dev/null 2>&1; then
      if java_settings="$(java -XshowSettings:properties -version 2>&1)"; then
        java_home="$(printf '%s\n' "$java_settings" | sed -n 's/^[[:space:]]*java.home = //p')"
        version="$(printf '%s\n' "$java_settings" | sed -n 's/^[[:space:]]*java.specification.version = //p')"
        if [ "$version" = "$JDK" ] && [ -x "$java_home/bin/javac" ]; then printf '%s\n' "$java_home"; exit 0; fi
      fi
    fi
    ;;
  sdk)
    if [ -n "${ANDROID_HOME:-}" ]; then printf '%s\n' "$ANDROID_HOME"; exit 0; fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then printf '%s\n' "$ANDROID_SDK_ROOT"; exit 0; fi
    if [ -f "$ROOT/local.properties" ]; then
      sdk_dir="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")"
      if [ -n "$sdk_dir" ]; then printf '%s\n' "$sdk_dir"; exit 0; fi
    fi
    for sdk_dir in /opt/homebrew/share/android-commandlinetools \
      /usr/local/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
      if [ -d "$sdk_dir" ]; then printf '%s\n' "$sdk_dir"; exit 0; fi
    done
    if [ "$(uname -s)" = Darwin ] && command -v brew >/dev/null 2>&1; then
      printf '%s/share/android-commandlinetools\n' "$(brew --prefix)"
    else
      printf '%s/Android/Sdk\n' "$HOME"
    fi
    ;;
  platform)
    # Integer compileSdk targets the base platform, even if a later minor SDK is installed.
    for platform in "$2/platforms/android-$3.0" "$2/platforms/android-$3"; do
      if [ -f "$platform/android.jar" ]; then printf '%s\n' "$platform"; exit 0; fi
    done
    exit 1
    ;;
  *) echo 'usage: environment.sh java|sdk|platform SDK_ROOT COMPILE_SDK' >&2; exit 2 ;;
esac
