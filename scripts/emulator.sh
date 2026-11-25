#!/usr/bin/env bash
# Start or reuse the requested AVD, and fail with its log instead of waiting forever.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="$("$ROOT/scripts/environment.sh" sdk)"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
mode=boot
if [ "${1:-}" = --serial ]; then mode=serial; shift; fi
AVD="${1:?usage: emulator.sh [--serial] AVD}"
BOOT_TIMEOUT="${EMULATOR_BOOT_TIMEOUT:-180}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

case "$BOOT_TIMEOUT" in ''|*[!0-9]*) echo 'ERROR: EMULATOR_BOOT_TIMEOUT must be seconds' >&2; exit 1 ;; esac
[ -x "$ADB" ] || { echo "ERROR: adb missing - run 'make setup-emulator'" >&2; exit 1; }

devices="$("$ADB" devices)"
serial=""
while read -r candidate state; do
  case "$candidate" in emulator-*) ;; *) continue ;; esac
  [ "$state" = device ] || continue
  name="$("$ADB" -s "$candidate" emu avd name 2>/dev/null | tr -d '\r' | head -1)" || continue
  if [ "$name" = "$AVD" ]; then serial="$candidate"; break; fi
done <<< "$devices"

if [ "$mode" = serial ]; then
  [ -n "$serial" ] || { echo "ERROR: no running emulator named '$AVD' - run 'make emulator AVD=$AVD'" >&2; exit 1; }
  printf '%s\n' "$serial"
  exit 0
fi
[ -x "$EMULATOR" ] || { echo "ERROR: emulator missing - run 'make setup-emulator'" >&2; exit 1; }
[ -f "$AVD_HOME/$AVD.ini" ] || { echo "ERROR: no AVD named '$AVD' - run 'make setup-emulator'" >&2; exit 1; }

pid=""
log=""
if [ -z "$serial" ]; then
  port=5554
  while printf '%s\n' "$devices" | awk '{print $1}' | grep -qx "emulator-$port"; do
    port=$((port + 2))
  done
  serial="emulator-$port"
  log="$(mktemp "${TMPDIR:-/tmp}/finador-emulator.XXXXXX")"
  "$EMULATOR" -avd "$AVD" -port "$port" -no-window -no-audio -no-boot-anim >"$log" 2>&1 &
  pid=$!
fi

deadline=$((SECONDS + BOOT_TIMEOUT))
while [ "$SECONDS" -lt "$deadline" ]; do
  if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then break; fi
  if [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
    echo "emulator ready: $serial ($AVD)"
    echo "select it explicitly when other devices are connected: ANDROID_SERIAL=$serial make run"
    exit 0
  fi
  sleep 1
done

echo "ERROR: $AVD did not boot within ${BOOT_TIMEOUT}s ($serial)." >&2
if [ -n "$log" ]; then
  echo "Emulator log: $log" >&2
  tail -40 "$log" >&2
fi
# Only the process this command started is disposable; an existing emulator remains untouched.
if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; fi
exit 1
