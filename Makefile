# Developer entry points for finador-android. Every target exports the JDK/SDK locations the
# Gradle wrapper needs, so `make test` works from a fresh shell with no profile sourced.
# Override on the command line if your paths differ: `make test ANDROID_HOME=/opt/sdk`.

JAVA_HOME ?= /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
ANDROID_HOME ?= /opt/homebrew/share/android-commandlinetools
export JAVA_HOME ANDROID_HOME

GRADLE := ./gradlew --console=plain
ADB := $(ANDROID_HOME)/platform-tools/adb
EMULATOR := $(ANDROID_HOME)/emulator/emulator

RELEASE_APK := app/build/outputs/apk/release/app-release.apk
APKSIGNER = $$(ls -d "$(ANDROID_HOME)"/build-tools/* 2>/dev/null | sort -V | tail -1)/apksigner
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
ASSET = $(DIST)/finador-android-v$(VERSION)$(if $(DEBUG_APK),-debug,).apk

.PHONY: help test test-class build install run reinstall release release-apk verify-signature \
	check-signing smoke-release gh-release gh-release-dry-run lint probe crossimpl emulator \
	emulator-kill clean

help: ## List available targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-18s %s\n", $$1, $$2}'

test: ## Run the full unit-test suite (host JVM, no device) - the main dev loop
	$(GRADLE) testDebugUnitTest
	@cat app/build/test-results/testDebugUnitTest/*.xml \
	  | grep -ho '\(tests\|failures\|errors\)="[0-9]*"' | tr -dc '0-9tfe="\n' \
	  | awk -F'"' '/^t/ {t+=$$2} /^f/ {f+=$$2} /^e/ {e+=$$2} END {printf "summary: %d tests, %d failures, %d errors\n", t, f, e}'

test-class: ## Run one test class, e.g. `make test-class T=GainsTest`
	$(GRADLE) testDebugUnitTest --tests "*$(T)*" --rerun-tasks

build: ## Compile the debug APK (catches Compose/Android compile errors)
	$(GRADLE) assembleDebug

install: ## Build and install the debug APK on the connected device/emulator
	$(GRADLE) installDebug

run: install ## Install, then (re)launch the app
	$(ADB) shell am start -n fin.android/.ui.MainActivity

reinstall: ## Uninstall then install the debug APK (fixes the signature mismatch after a release smoke)
	-$(ADB) uninstall fin.android
	$(GRADLE) installDebug

release: ## Build the R8-minified release APK (real key when configured, else debug-signed)
	$(GRADLE) assembleRelease
	@ls -lh $(RELEASE_APK) | awk '{print "APK: '"$(RELEASE_APK)"' (" $$5 ")"}'

check-signing: ## Say whether a real release keystore is configured on this machine (no secret printed)
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
	  echo "One-time setup - create a key OUTSIDE the repo:"; \
	  echo "  keytool -genkeypair -v -keystore \$$HOME/finador-release.jks -alias finador \\"; \
	  echo "      -keyalg RSA -keysize 4096 -validity 10000"; \
	  echo "  chmod 600 \$$HOME/finador-release.jks"; \
	  echo; \
	  echo "Then declare it in ~/.gradle/gradle.properties (never in this repo):"; \
	  echo "  FINADOR_STORE_FILE=\$$HOME/finador-release.jks"; \
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
	$(APKSIGNER) verify --print-certs $(RELEASE_APK)

release-apk: check-signing release ## Build, signature-verify and stage the installable APK as app/build/dist/finador-android-v<version>.apk
	@test -n "$(VERSION)" || { echo "ERROR: cannot read versionName from app/build.gradle.kts"; exit 1; }
	@test -x "$(APKSIGNER)" || { \
	  echo "ERROR: apksigner not found under $(ANDROID_HOME)/build-tools - cannot verify the APK's"; \
	  echo "signature, and an unverified APK is not something to publish. Install the build-tools:"; \
	  echo "  sdkmanager 'build-tools;36.0.0'"; \
	  exit 1; \
	}
	$(APKSIGNER) verify --verbose --print-certs $(RELEASE_APK)
	@if $(APKSIGNER) verify --print-certs $(RELEASE_APK) | grep -q "CN=Android Debug"; then \
	  if [ -z "$(DEBUG_APK)" ]; then \
	    echo "ERROR: the built APK is DEBUG-signed - refusing to name it a release."; \
	    $(MAKE) --no-print-directory check-signing DEBUG_APK=; exit 1; \
	  fi; \
	  echo "note: debug-signed, as DEBUG_APK=1 requested"; \
	elif [ -n "$(DEBUG_APK)" ]; then \
	  echo "ERROR: DEBUG_APK=1 but the APK is signed with the REAL key - drop the flag."; exit 1; \
	fi
	@mkdir -p $(DIST)
	@cp $(RELEASE_APK) "$(ASSET)"
	@ls -lh "$(ASSET)" | awk '{print "asset: '"$(ASSET)"' (" $$5 ")"}'

smoke-release: ## Install the release APK on the emulator (WIPES app state), launch, check for crashes
	-$(ADB) uninstall fin.android
	$(ADB) install $(RELEASE_APK)
	$(ADB) logcat -c
	$(ADB) shell am start -n fin.android/.ui.MainActivity
	sleep 6
	$(ADB) exec-out screencap -p > /tmp/finador-release-smoke.png
	@if $(ADB) logcat -d -s AndroidRuntime:E | grep -v '^---------' | grep -q .; then \
	  echo "SMOKE FAIL - crash in logcat:"; $(ADB) logcat -d -s AndroidRuntime:E | tail -20; exit 1; \
	else \
	  echo "SMOKE OK (inspect the screen: /tmp/finador-release-smoke.png)"; \
	fi

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

probe: ## Hit the REAL market-data providers over the network and print what came back (not part of `make test`)
	$(GRADLE) testDebugUnitTest --tests "*LiveProviderProbe*" --rerun-tasks -Dprobe=1 -i \
	  | grep -E "^probe |FAILED|live providers"

lint: ## Android Lint; report in app/build/reports/lint-results-debug.txt
	$(GRADLE) lintDebug

crossimpl: ## Cross-implementation gate against the Go reference (builds /tmp/finador first)
	cd ../finador && go build -trimpath -o /tmp/finador ./cmd/finador
	scripts/crossimpl.sh

emulator: ## Boot the headless test emulator (AVD "test") and wait until it is ready
	$(EMULATOR) -avd test -no-window -no-audio -no-boot-anim >/dev/null 2>&1 &
	$(ADB) wait-for-device
	until [ "$$($(ADB) shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
	@echo "emulator ready"

emulator-kill: ## Shut the emulator down
	$(ADB) emu kill

clean: ## Delete build outputs
	$(GRADLE) clean
