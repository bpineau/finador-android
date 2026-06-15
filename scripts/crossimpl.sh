#!/usr/bin/env bash
# Cross-implementation compatibility gate: prove an Android-written .fin opens in the Go finador
# CLI, and a Go-written .fin opens in the Android reader. Requires a built `finador` binary.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

# Default to this script's repo and a sibling `finador` checkout; override via env if elsewhere.
AROOT="${AROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
FROOT="${FROOT:-$(cd "$AROOT/.." && pwd)/finador}"
FIN="${FIN:-/tmp/finador}"
OUT="$AROOT/build/crossimpl"
SAMPLE="$FROOT/docs/format-testdata/sample.ledger"
SPW="finador-format-spec-v3"
GPW="gopw"

rm -rf "$OUT"; mkdir -p "$OUT"
run_fin() { FINADOR_PASSWORD="$1" "$FIN" --offline --no-keychain --db "$2" "${@:3}" </dev/null; }

echo "### 1. baseline - Go reads the committed sample"
run_fin "$SPW" "$SAMPLE" account list
run_fin "$SPW" "$SAMPLE" tx list | tail -n +1

echo "### 2. Android produces android-mutated.fin (sample + a deposit)"
"$AROOT/gradlew" --project-dir "$AROOT" testDebugUnitTest \
  --tests "fin.android.format.CrossImplProducerTest" --rerun-tasks \
  -Dcrossimpl.out="$OUT" --console=plain </dev/null
ls -l "$OUT/android-mutated.fin"

echo "### 3. Go reads the Android-mutated file (incl. an Android-written account + asset)"
ACCTS=$(run_fin "$SPW" "$OUT/android-mutated.fin" account list)
echo "$ACCTS"
echo "$ACCTS" | grep -q "Android Test Account" || { echo "FAIL: Go did not read the Android-written account"; exit 1; }
ASSETS=$(run_fin "$SPW" "$OUT/android-mutated.fin" asset list)
echo "$ASSETS"
echo "$ASSETS" | grep -q "Android Test Asset" || { echo "FAIL: Go did not read the Android-written asset"; exit 1; }
run_fin "$SPW" "$OUT/android-mutated.fin" tx list

echo "### 4. Go creates go-created.fin (init + account + cash deposit)"
run_fin "$GPW" "$OUT/go-created.fin" init
run_fin "$GPW" "$OUT/go-created.fin" account add "Mon CTO" --tax gains:30%
run_fin "$GPW" "$OUT/go-created.fin" cash deposit "Mon CTO" 1234 2026-01-05

echo "### 5. Android reads the Go-created file"
"$AROOT/gradlew" --project-dir "$AROOT" testDebugUnitTest \
  --tests "fin.android.format.CrossImplConsumerTest" --rerun-tasks \
  -Dcrossimpl.go.file="$OUT/go-created.fin" -Dcrossimpl.go.pw="$GPW" --console=plain </dev/null

echo "### CROSS-IMPL OK"
