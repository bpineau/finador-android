# Developer entry points for finador-android. Every target exports the JDK/SDK locations the
# Gradle wrapper needs, so `make test` works from a fresh shell with no profile sourced.
# Override on the command line if your paths differ: `make test ANDROID_HOME=/opt/sdk`.

JAVA_HOME ?= /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
ANDROID_HOME ?= /opt/homebrew/share/android-commandlinetools
export JAVA_HOME ANDROID_HOME

GRADLE := ./gradlew --console=plain
ADB := $(ANDROID_HOME)/platform-tools/adb
EMULATOR := $(ANDROID_HOME)/emulator/emulator

.PHONY: help test test-class build install run release lint crossimpl emulator emulator-kill clean

help: ## List available targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

test: ## Run the full unit-test suite (host JVM, no device) - the main dev loop
	$(GRADLE) testDebugUnitTest

test-class: ## Run one test class, e.g. `make test-class T=GainsTest`
	$(GRADLE) testDebugUnitTest --tests "*$(T)*" --rerun-tasks

build: ## Compile the debug APK (catches Compose/Android compile errors)
	$(GRADLE) assembleDebug

install: ## Build and install the debug APK on the connected device/emulator
	$(GRADLE) installDebug

run: install ## Install, then (re)launch the app
	$(ADB) shell am start -n fin.android/.ui.MainActivity

release: ## Build the R8-minified release APK (real key when configured, else debug-signed)
	$(GRADLE) assembleRelease

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
