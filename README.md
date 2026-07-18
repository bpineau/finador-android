# finador-android

A **native Android** client (Kotlin / Jetpack Compose) - the mobile companion to
[finador](../finador) (desktop CLI + web). It reads and writes **the same encrypted
`.fin` file**, synced through a **private GitHub repository**, so you manage your wealth
interchangeably from desktop and mobile.

- **Scope**: full read (gross/tax/net value, gains, per-asset detail), quick transaction entry,
  and account/asset management (Settings → Manage accounts / Manage assets) - at parity with the
  desktop CLI and web for everyday operations.
- **Compatibility**: bit-for-bit with `finador/docs/FORMAT.md` (verified by the format test
  vectors and a cross-implementation test against the Go binary).
- **Storage**: GitHub only (the encrypted `.fin` never leaves the repo in clear text); market
  quotes fetched on-device (Yahoo → FT → Morningstar) with a local encrypted cache.

---

## 1. Set up the dev environment (macOS, free)

> For a developer **new to Android**: you do **not** have to install Gradle or Android Studio -
> the project ships a wrapper (`./gradlew`) that downloads the right Gradle version, and the
> command line is enough to build/install. Android Studio is still handy (editor, emulator
> manager, logs).

```sh
# JDK 21 (Temurin) + Android command-line tools (SDK) + (optional) the IDE
brew install --cask temurin@21
brew install --cask android-commandlinetools
brew install --cask android-studio          # optional but convenient

# Environment variables (add to ~/.zshrc to make them permanent)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin"

# SDK components (accept the licenses, then install)
sdkmanager --licenses                                              # accept (yes)
sdkmanager "platform-tools"                                        # adb, fastboot
sdkmanager "platforms;android-36"                                  # target API
sdkmanager "build-tools;36.1.0"
sdkmanager "emulator" "system-images;android-36;google_apis;arm64-v8a"

# Create an emulator named "test"
echo no | avdmanager create avd -n test -k "system-images;android-36;google_apis;arm64-v8a"
```

> **Apple Silicon**: use the `arm64-v8a` images (fast, native virtualization).
> On an Intel Mac, use `x86_64` instead.

### The SDK must be discoverable by the build

`./gradlew` locates the SDK via **`ANDROID_HOME`** (exported above) **or** via a `local.properties`
file at the repo root (not version-controlled). If you don't export `ANDROID_HOME` in your shell,
create that file once:

```sh
echo "sdk.dir=$(brew --prefix)/share/android-commandlinetools" > local.properties
```

---

## 2. Build & test

Always with `JAVA_HOME`/`ANDROID_HOME` exported (see above):

```sh
cd finador-android               # the cloned repo

./gradlew testDebugUnitTest      # unit tests (JVM, no device) - the engine layer
./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # install on the connected device/emulator
```

- **First run** is slow (downloads Gradle + dependencies). Subsequent runs are fast.
- The **debug build** is for everyday work. An optimized **release build** (R8: faster, ~3× smaller)
  also exists: `./gradlew installRelease`. It is signed with a real release key when the maintainer's
  `FINADOR_STORE_FILE`/`FINADOR_STORE_PASSWORD`/`FINADOR_KEY_ALIAS`/`FINADOR_KEY_PASSWORD` are set in
  `~/.gradle/gradle.properties` (never committed); without them it falls back to debug signing so the
  repo still builds for everyone.

---

## 3. Run on the emulator

The emulator must be running **before** `installDebug` (otherwise: *"No connected devices"*).
In a **separate terminal** (it stays in the foreground):

```sh
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
$ANDROID_HOME/emulator/emulator -avd test           # leave it running
# list AVDs: $ANDROID_HOME/emulator/emulator -list-avds
```

Then, once the Android home screen is up, from the project terminal:

```sh
./gradlew installDebug
adb shell am start -n fin.android/.ui.MainActivity   # (re)launch without reinstalling
```

Stop the emulator: close its window, or `adb emu kill`.
(GUI alternative: Android Studio → *Device Manager* → ▶ - more convenient.)

---

## 4. Run on a real phone (e.g. Galaxy S21)

1. Enable **developer mode**: *Settings → About → tap "Build number" 7 times*.
2. Enable **USB debugging**: *Settings → Developer options → USB debugging*.
3. Plug the phone in over USB and accept the authorization prompt.
4. Check it's visible: `adb devices` (should list a device).
5. `./gradlew installDebug`, then open the app from the app drawer.

This is also the best way to judge the real look (the emulator distorts proportions a bit
compared to a tall screen like the S21).

---

## 5. Tips (for developers new to Android)

- **`adb logcat`** = the device logs. To see only this app:
  `adb logcat -s fin.android:V AndroidRuntime:E` (a crash shows up as `AndroidRuntime: FATAL`).
- **Clean reinstall**: `adb uninstall fin.android` then `installDebug` (also wipes the stored
  config + secrets).
- **Screenshots**: `adb exec-out screencap -p > shot.png`.
- **`local.properties`, `build/`, `.gradle/`, `*.fin`** are not version-controlled (see `.gitignore`).
- **No need to install Gradle**: `./gradlew` downloads the pinned version (8.13). You can still
  `brew install gradle` for a global `gradle` command (not required here).
- **Versions**: compileSdk/targetSdk **36**, minSdk **26**, JDK **21**, AGP 8.13.2, Kotlin 2.2.20.
- **Onboarding (first launch)**: you need a **private GitHub repo** + a **fine-grained PAT**
  (Settings → Developer settings → Personal access tokens → Fine-grained; *Repository access* =
  that one repo; *Permissions → Contents: Read and write*). Paste the token + a passphrase; after
  that it's biometric unlock.

---

## 6. Layout & docs

```
app/src/main/kotlin/fin/android/
  crypto/  domain/  format/    # pure-Kotlin core (Argon2id/HKDF/AES-GCM, model, .fin read/write)
  remote/  market/  valuation/ # GitHub sync, multi-source quotes, valuation/perf/gains
  data/    ui/                 # DI + repository, Compose screens
scripts/crossimpl.sh           # bidirectional compatibility test against the Go binary
docs/superpowers/              # spec, plan, decision log
```

The file format and sync model are specified on the finador side:
`../finador/docs/FORMAT.md` and `../finador/docs/superpowers/specs/2026-06-13-github-remote-data-design.md`.
