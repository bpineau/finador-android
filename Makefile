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
APKSIGNER = $$(ls -d "$(ANDROID_HOME)"/build-tools/* | sort -V | tail -1)/apksigner
# The app version is read from the build file (single source of truth); lazy so it is
# evaluated when a target runs, after any bump commit.
VERSION = $(shell sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)

.PHONY: help test test-class build install run reinstall release verify-signature smoke-release \
	gh-release lint crossimpl emulator emulator-kill clean

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

verify-signature: ## Print the release APK's signing certs (CN=Android Debug means: do NOT publish)
	$(APKSIGNER) verify --print-certs $(RELEASE_APK)

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

gh-release: ## End-to-end release of v<versionName>: tests, signed APK, tag+push, GitHub release with the APK. Bump versionName/versionCode + commit first. NOTES=file.md for hand-written notes (default: GitHub-generated).
	@test -z "$$(git status --porcelain)" || { echo "ERROR: working tree not clean - commit the version bump first"; exit 1; }
	@test -n "$(VERSION)" || { echo "ERROR: cannot read versionName from app/build.gradle.kts"; exit 1; }
	@! git rev-parse -q --verify "refs/tags/v$(VERSION)" >/dev/null \
	  || { echo "ERROR: tag v$(VERSION) already exists - bump versionName/versionCode and commit first"; exit 1; }
	$(MAKE) test
	$(MAKE) release
	@if $(APKSIGNER) verify --print-certs $(RELEASE_APK) | grep -q "CN=Android Debug"; then \
	  echo "ERROR: APK is debug-signed - set FINADOR_STORE_FILE & co. in ~/.gradle/gradle.properties"; exit 1; \
	fi
	git tag -a "v$(VERSION)" -m "v$(VERSION)"
	git push origin master "v$(VERSION)"
	cp $(RELEASE_APK) "/tmp/finador-android-v$(VERSION).apk"
	gh release create "v$(VERSION)" "/tmp/finador-android-v$(VERSION).apk" \
	  --title "v$(VERSION)" $(if $(NOTES),--notes-file "$(NOTES)",--generate-notes)
	@echo "released: https://github.com/bpineau/finador-android/releases/tag/v$(VERSION)"

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
