# Developer entry points for finador-android. Every target exports the JDK/SDK locations the
# Gradle wrapper needs, so `make test` works from a fresh shell with no profile sourced.
# Override on the command line if your paths differ: `make test ANDROID_HOME=/opt/sdk`.
#
# Nothing installed yet? `make setup` (macOS) does it, and `make doctor` says what is missing.

# Read from the build files, so this file cannot ask for a version the build does not want.
JDK := $(shell sed -n 's/.*JavaLanguageVersion.of(\([0-9]*\)).*/\1/p' app/build.gradle.kts)
COMPILE_SDK := $(shell sed -n 's/.*compileSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts)

# Setup, doctor and make use the same environment discovery, including Linux's JDK on PATH.
JAVA_HOME ?= $(shell scripts/environment.sh java)
ANDROID_HOME ?= $(shell scripts/environment.sh sdk)

export JAVA_HOME ANDROID_HOME

GRADLE := ./gradlew --console=plain
ADB := $(ANDROID_HOME)/platform-tools/adb
EMULATOR := $(ANDROID_HOME)/emulator/emulator
# The AVD the emulator targets boot; `make setup-emulator` creates test$(COMPILE_SDK).
AVD ?= test$(COMPILE_SDK)

RELEASE_APK := app/build/outputs/apk/release/app-release.apk
LINT_REPORT := app/build/reports/lint-results-debug.txt
APKSIGNER = $$(ls -d "$(ANDROID_HOME)"/build-tools/* 2>/dev/null | sort -V | tail -1)/apksigner
ZIPALIGN = $$(ls -d "$(ANDROID_HOME)"/build-tools/* 2>/dev/null | sort -V | tail -1)/zipalign
# The app version is read from the build file (single source of truth); lazy so it is
# evaluated when a target runs, after any bump commit.
VERSION = $(shell sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)

# ---------------------------------------------------------------------------
# Release signing - the key NEVER enters this repo
#
# FINADOR_STORE_FILE / FINADOR_STORE_PASSWORD / FINADOR_KEY_ALIAS /
# FINADOR_KEY_PASSWORD are read by app/build.gradle.kts from
# ~/.gradle/gradle.properties (never committed) or from the environment. The
# keystore itself lives outside the working tree (~/finador-release.jks by
# convention). Nothing about it is committed, printed or uploaded.
#
# Without them the release build falls back to DEBUG signing. That is fine for a
# local build and a disaster under a release name: a debug-signed APK is signed
# with a key everyone has, and it cannot upgrade an installed app. So the
# publishing targets refuse it - unless DEBUG_APK=1 asks for one deliberately,
# and the asset is then named "-debug" so nobody mistakes it.
# ---------------------------------------------------------------------------
DIST := app/build/dist
# Public certificate of the published APK. A different signing key cannot upgrade existing installs.
RELEASE_CERT_SHA256 := 69e55f2a91607abb4b5c6a3fd3580bde774f5a26ced6d3c89661f4812c1bf472
ASSET = $(DIST)/finador-android-v$(VERSION)$(if $(DEBUG_APK),-debug,).apk

.PHONY: help doctor setup setup-emulator preflight test test-class build install run reinstall \
	release release-apk verify-signature check-signing smoke-release gh-release \
	gh-release-dry-run lint outdated probe probe-device crossimpl emulator emulator-kill clean

help: ## List available targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-18s %s\n", $$1, $$2}'

doctor: ## Check this machine against what the build needs; print OK/MISSING and the fix. Changes nothing.
	@scripts/doctor.sh

setup: ## Install whatever `make doctor` reports missing (macOS/Homebrew; says what to do elsewhere). Idempotent, never touches a signing key.
	@scripts/setup.sh

setup-emulator: ## Same, plus the emulator, a system image for the build's compileSdk and the AVD the emulator targets boot (~1.5 GB)
	@scripts/setup.sh --emulator

# A few file checks, no subprocess beyond one shell: enough to turn "Gradle exploded in 400 lines"
# into "run make doctor". Anything subtler belongs in `make doctor`, which is opt-in.
preflight:
	@test -n "$(JAVA_HOME)" && test -x "$(JAVA_HOME)/bin/java" && test -x "$(JAVA_HOME)/bin/javac" || { \
	  echo "ERROR: no JDK $(JDK) at '$(JAVA_HOME)' - run 'make doctor' (or 'make setup')"; exit 1; }
	@test -d "$(ANDROID_HOME)/cmdline-tools" -o -d "$(ANDROID_HOME)/platforms" || { \
	  echo "ERROR: no Android SDK at '$(ANDROID_HOME)' - run 'make doctor' (or 'make setup')"; exit 1; }

test: preflight ## Run the full unit-test suite (host JVM, no device) - the main dev loop
	$(GRADLE) testDebugUnitTest
	@cat app/build/test-results/testDebugUnitTest/*.xml \
	  | grep -ho '\(tests\|failures\|errors\)="[0-9]*"' | tr -dc '0-9tfe="\n' \
	  | awk -F'"' '/^t/ {t+=$$2} /^f/ {f+=$$2} /^e/ {e+=$$2} END {printf "summary: %d tests, %d failures, %d errors\n", t, f, e}'

test-class: preflight ## Run one test class, e.g. `make test-class T=GainsTest`
	$(GRADLE) testDebugUnitTest --tests "*$(T)*" --rerun-tasks

build: preflight ## Compile the debug APK (catches Compose/Android compile errors)
	$(GRADLE) assembleDebug

install: preflight ## Build and install the debug APK on the connected device/emulator
	$(GRADLE) installDebug

run: install ## Install, then (re)launch the app
	$(ADB) shell am start -n fin.android/.ui.MainActivity

reinstall: build ## Wipe and reinstall on the named AVD, or the device explicitly selected with ANDROID_SERIAL
	@set -e; serial="$${ANDROID_SERIAL:-}"; \
	  if [ -z "$$serial" ]; then serial=$$(scripts/emulator.sh --serial "$(AVD)"); fi; \
	  "$(ADB)" -s "$$serial" uninstall fin.android >/dev/null 2>&1 || :; \
	  "$(ADB)" -s "$$serial" install app/build/outputs/apk/debug/app-debug.apk

release: preflight ## Build the R8-minified release APK (real key when configured, else debug-signed)
	$(GRADLE) assembleRelease
	@ls -lh $(RELEASE_APK) | awk '{print "APK: '"$(RELEASE_APK)"' (" $$5 ")"}'

check-signing: ## Say whether a real release keystore is configured on this machine (no secret printed)
	@case "$(DEBUG_APK)" in ''|1) ;; *) echo "ERROR: DEBUG_APK must be unset or 1"; exit 1 ;; esac
	@if [ -n "$$FINADOR_STORE_FILE" ]; then \
	  echo "release keystore: configured (environment)"; \
	elif grep -qE '^[[:space:]]*FINADOR_STORE_FILE[[:space:]]*=' "$$HOME/.gradle/gradle.properties" 2>/dev/null; then \
	  echo "release keystore: configured (~/.gradle/gradle.properties)"; \
	elif [ -n "$(DEBUG_APK)" ]; then \
	  echo "release keystore: absent - DEBUG_APK=1, so a debug-signed APK is what you asked for"; \
	else \
	  echo "ERROR: no release keystore configured, so the release build would be DEBUG-signed."; \
	  echo "A debug-signed APK is signed with a key everyone has and cannot upgrade an installed app."; \
	  echo; \
	  echo "Restore the existing release key. Only for a NEW distribution, generate a key:"; \
	  echo "  keytool -genkeypair -v -keystore \$$HOME/finador-release.jks -alias finador \\"; \
	  echo "      -keyalg RSA -keysize 4096 -validity 10000"; \
	  echo "  chmod 600 \$$HOME/finador-release.jks"; \
	  echo; \
	  echo "Then declare it in ~/.gradle/gradle.properties (never in this repo):"; \
	  echo "  FINADOR_STORE_FILE=/absolute/path/to/finador-release.jks"; \
	  echo "  FINADOR_STORE_PASSWORD=..."; \
	  echo "  FINADOR_KEY_ALIAS=finador"; \
	  echo "  FINADOR_KEY_PASSWORD=..."; \
	  echo "  chmod 600 ~/.gradle/gradle.properties"; \
	  echo; \
	  echo "The same four names are read from the environment when the file has none (CI secrets)."; \
	  echo "To publish a debug-signed APK ON PURPOSE, re-run with DEBUG_APK=1: the asset is then"; \
	  echo "named finador-android-v<version>-debug.apk."; \
	  exit 1; \
	fi

verify-signature: ## Print the release APK's signing certs (CN=Android Debug means: do NOT publish)
	"$(APKSIGNER)" verify --print-certs "$(RELEASE_APK)"

release-apk: check-signing release ## Build, signature-verify and stage the installable APK as app/build/dist/finador-android-v<version>.apk
	@test -n "$(VERSION)" || { echo "ERROR: cannot read versionName from app/build.gradle.kts"; exit 1; }
	@test -x "$(APKSIGNER)" || { \
	  echo "ERROR: apksigner not found under $(ANDROID_HOME)/build-tools - cannot verify the APK's"; \
	  echo "signature, and an unverified APK is not something to publish. Install the build-tools:"; \
	  echo "  make setup"; \
	  exit 1; \
	}
	@set -e; certs=$$("$(APKSIGNER)" verify --verbose --print-certs "$(RELEASE_APK)"); \
	  printf '%s\n' "$$certs"; \
	  if [ "$(DEBUG_APK)" = 1 ]; then \
	    printf '%s\n' "$$certs" | grep -Eq 'certificate DN: .*CN=Android Debug(,|$$)' || \
	      { echo "ERROR: DEBUG_APK=1 requires a debug-signed APK"; exit 1; }; \
	  else \
	    signer=$$(printf '%s\n' "$$certs" | sed -n 's/.* certificate SHA-256 digest: //p' | sort -u); \
	    [ "$$signer" = "$(RELEASE_CERT_SHA256)" ] || \
	      { echo "ERROR: APK signer differs from the published release certificate"; exit 1; }; \
	  fi
	@test -x "$(ZIPALIGN)" || { echo "ERROR: zipalign missing - run 'make setup'"; exit 1; }
	"$(ZIPALIGN)" -c -P 16 4 "$(RELEASE_APK)"
	@mkdir -p $(DIST)
	@cp $(RELEASE_APK) "$(ASSET)"
	@ls -lh "$(ASSET)" | awk '{print "asset: '"$(ASSET)"' (" $$5 ")"}'

smoke-release: ## Install the release APK on the named running AVD (WIPES its app state), launch, check for crashes
	@test -s "$(RELEASE_APK)" || { echo "ERROR: release APK missing - run 'make release'"; exit 1; }
	@set -e; serial=$$(scripts/emulator.sh --serial "$(AVD)"); \
	  adb() { "$(ADB)" -s "$$serial" "$$@"; }; \
	  adb uninstall fin.android >/dev/null 2>&1 || :; \
	  adb install "$(RELEASE_APK)"; \
	  adb logcat -c; \
	  adb shell am start -W -n fin.android/.ui.MainActivity; \
	  sleep 6; \
	  adb shell pidof fin.android >/dev/null || { echo "SMOKE FAIL - app exited"; adb logcat -d -s AndroidRuntime:E; exit 1; }; \
	  adb exec-out screencap -p > /tmp/finador-release-smoke.png; \
	  crashes=$$(adb logcat -d -s AndroidRuntime:E); \
	  if printf '%s\n' "$$crashes" | grep -v '^---------' | grep -q .; then \
	    echo "SMOKE FAIL - crash in logcat:"; printf '%s\n' "$$crashes" | tail -20; exit 1; \
	  fi; \
	  echo "SMOKE OK (inspect the screen: /tmp/finador-release-smoke.png)"

gh-release: ## End-to-end release of v<versionName>: tests, cross-impl gate, signed APK, tag+push, GitHub release with the APK attached. Bump versionName/versionCode + commit first. NOTES=file.md for hand-written notes; DEBUG_APK=1 to attach a debug-signed APK on purpose.
	$(MAKE) gh-release-dry-run
	@if git rev-parse -q --verify "refs/tags/v$(VERSION)" >/dev/null; then \
	  echo "tag v$(VERSION) already exists at HEAD: re-running, nothing to tag"; \
	else \
	  git tag -a "v$(VERSION)" -m "v$(VERSION)"; \
	fi
	git push origin master "v$(VERSION)"
	@if gh release view "v$(VERSION)" >/dev/null 2>&1; then \
	  echo "release v$(VERSION) exists: replacing its asset"; \
	  gh release upload "v$(VERSION)" "$(ASSET)" --clobber; \
	else \
	  gh release create "v$(VERSION)" "$(ASSET)" \
	    --title "v$(VERSION)" $(if $(NOTES),--notes-file "$(NOTES)",--generate-notes); \
	fi
	@echo "released: https://github.com/bpineau/finador-android/releases/tag/v$(VERSION)"

gh-release-dry-run: ## Everything gh-release does EXCEPT the tag, the push and the GitHub release: gates, APK, signature check, and what would be uploaded
	@test -n "$(VERSION)" || { echo "ERROR: cannot read versionName from app/build.gradle.kts"; exit 1; }
	@test -z "$$(git status --porcelain)" || { echo "ERROR: working tree not clean - commit the version bump first"; exit 1; }
	@if git rev-parse -q --verify "refs/tags/v$(VERSION)" >/dev/null; then \
	  test "$$(git rev-parse "v$(VERSION)^{commit}")" = "$$(git rev-parse HEAD)" \
	    || { echo "ERROR: tag v$(VERSION) exists and points elsewhere - bump versionName/versionCode and commit first"; exit 1; }; \
	fi
	$(MAKE) test
	$(MAKE) crossimpl
	$(MAKE) release-apk
	@echo
	@echo "DRY RUN - nothing was tagged, pushed or published."
	@echo "  tag:      v$(VERSION) at $$(git rev-parse --short HEAD)"
	@echo "  asset:    $(ASSET)"
	@echo "  sha256:   $$(shasum -a 256 "$(ASSET)" | cut -d' ' -f1)"
	@echo "  upload:   gh release $$(gh release view "v$(VERSION)" >/dev/null 2>&1 && echo 'upload --clobber' || echo create) v$(VERSION) $(ASSET)"
	@echo "  notes:    $(if $(NOTES),--notes-file $(NOTES),--generate-notes)"

# The probe's lines are read back from the JUnit XML rather than from Gradle's console: `-i` prints
# a test's stdout only when Gradle feels like it, and a silent probe reads as a broken probe.
probe: preflight ## Hit the REAL providers from the HOST JVM (fast feedback, NOT the release gate: see probe-device)
	@$(GRADLE) testDebugUnitTest --tests "*LiveProviderProbe*" --rerun-tasks -Dprobe=1; \
	  status=$$?; \
	  sed 's/.*<!\[CDATA\[//' app/build/test-results/testDebugUnitTest/TEST-*LiveProviderProbe.xml \
	    | grep '^probe '; \
	  exit $$status

# The gate. A phone's TLS is Conscrypt and the host JVM's is JSSE: the two send different
# ClientHellos, providers fingerprint them, so a green `make probe` cannot speak for a release.
# See net/Tls.kt. Needs a booted emulator (`make emulator`) or a plugged-in phone.
probe-device: preflight ## Run that same probe ON the device/emulator - the ONE live check a release owes
	@$(ADB) get-state >/dev/null 2>&1 || { \
	  echo "ERROR: no device or emulator connected - run 'make emulator' first"; exit 1; }
	$(ADB) logcat -c
	$(GRADLE) connectedDebugAndroidTest
	@$(ADB) logcat -d -s probe:I -v raw | grep '^probe '

lint: preflight ## Android Lint, failing on any warning; report in $(LINT_REPORT)
	$(GRADLE) lintDebug
	@tail -1 $(LINT_REPORT)

outdated: preflight ## Newer dependency versions lint knows of; informational, never a gate
	$(GRADLE) lintDebug
	@test -f "$(LINT_REPORT)" && test -r "$(LINT_REPORT)" || { \
	  echo "ERROR: cannot read lint report: $(LINT_REPORT)" >&2; exit 1; }
	@awk '/\[(GradleDependency|AndroidGradlePluginVersion|NewerVersionAvailable)\]/ \
	  { sub(/^.*: Hint: /, ""); print; found = 1 } \
	  END { if (!found) print "no newer version known to lint" }' "$(LINT_REPORT)"

crossimpl: preflight ## Cross-implementation gate against the Go reference (builds /tmp/finador first)
	cd ../finador && go build -trimpath -o /tmp/finador ./cmd/finador
	scripts/crossimpl.sh

emulator: ## Boot the headless test emulator and wait until it is ready (override the AVD with AVD=name)
	@scripts/emulator.sh "$(AVD)"

emulator-kill: ## Shut the named running AVD down (override with AVD=name)
	@serial=$$(scripts/emulator.sh --serial "$(AVD)") && "$(ADB)" -s "$$serial" emu kill

clean: preflight ## Delete build outputs
	$(GRADLE) clean
