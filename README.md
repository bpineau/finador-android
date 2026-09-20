# finador-android

A **native Android** client (Kotlin / Jetpack Compose) - the mobile companion to
[finador](../finador) (desktop CLI + web). It reads and writes **the same encrypted
`.fin` file**, synced through a **private GitHub repository**, so you manage your wealth
interchangeably from desktop and mobile.

- **Scope**: full read (gross/tax/net value, gains, per-asset detail), quick transaction entry,
  and account/asset management (Settings -> Manage accounts / Manage assets) - at parity with the
  desktop CLI and web for everyday operations.
- **Compatibility**: bit-for-bit with `finador/docs/FORMAT.md` (verified by the format test
  vectors and a cross-implementation test against the Go binary).
- **Storage**: GitHub only (the encrypted `.fin` never leaves the repo in clear text); market
  quotes fetched on-device (Airfund -> Yahoo -> FT -> Morningstar) with a local encrypted cache.

No prior Android knowledge is assumed anywhere below: every command and every yearly chore is
spelled out.

---

## 1. Start here (macOS, free, one command)

```sh
git clone https://github.com/bpineau/finador-android && cd finador-android
make setup     # installs the JDK + Android SDK and accepts the SDK licences (idempotent)
make test      # the unit suite on the host JVM, no phone, no emulator
make build     # compiles the debug APK
```

`make setup` needs [Homebrew](https://brew.sh) and nothing else. It is safe to re-run: every step
checks first and prints "already here". It never touches a signing key.

```sh
make doctor    # read-only: what is installed, what is missing, and the exact fix command
make help      # every target
```

**Linux**: install a JDK 21 with your package manager and unpack the
["command line tools only"](https://developer.android.com/studio#command-line-tools-only) archive
so that `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager` exists. Then
`ANDROID_HOME=/path/to/sdk make setup` does the rest (licences, platform, build-tools). `make
setup` prints those two steps for you if you run it on Linux with nothing installed.

### How the build is pinned

| Piece | What it guarantees |
|---|---|
| `./gradlew` (the wrapper) | downloads **Gradle 9.7.1** and checks its SHA-256 before running. Never `brew install gradle`. |
| `gradle/libs.versions.toml` | the one list of dependencies, each at an **exact** version: no `+`, no ranges, no snapshots. |
| a version-catalog bump + `make test build lint crossimpl` | how a dependency is upgraded and proven. |
| `make test` | the unit suite on the host JVM, no device needed (the engine layers are pure Kotlin). |
| `make build` | the debug APK. |
| `make doctor` / `make setup` | check / install everything the build needs (JDK, SDK, platform, build-tools, licences). |
| the Java **toolchain** in `app/build.gradle.kts` | pins the JDK the build runs on (21), independently of what is on your PATH. |

What the app is built with, all pinned: **JDK 21**, Gradle **9.7.1**, Android Gradle Plugin
**9.4.1**, Kotlin **2.4.20**, Compose BOM **2026.09.00**, `compileSdk`/`targetSdk` **37**,
`minSdk` **26**.

---

## 2. Everyday commands

```sh
make test                 # the main loop: compiles main + test, runs the suite (no device)
make test-class T=Gains   # one class, when you know where you broke it
make build                # compile the debug APK (catches Compose/Android errors make test cannot)
make lint                 # Android Lint; must stay at "No issues found"
make crossimpl            # byte-compatibility gate against the Go implementation (needs ../finador)
make probe                # hit the REAL quote providers from the host JVM (never part of make test)
make probe-device         # the same probe ON a device/emulator: the one live check a release owes
make clean                # delete build outputs
```

The Makefile exports `JAVA_HOME`/`ANDROID_HOME` itself, so every target works from a shell with no
profile sourced. Override them if your paths differ: `make test ANDROID_HOME=/opt/sdk`.

`make test` prints `summary: N tests, 0 failures, 0 errors`; the per-test detail is in
`app/build/test-results/testDebugUnitTest/*.xml` and `app/build/reports/tests/`.

---

## 3. Run it on a phone

```sh
# On the phone, three taps' worth of setup:
#   Settings -> About phone -> tap "Build number" seven times   (enables developer mode)
#   Settings -> System -> Developer options -> USB debugging    (turn on)
#   plug it in over USB, then accept the "Allow USB debugging?" prompt on the screen
adb devices               # $ANDROID_HOME/platform-tools/adb - should list your phone
make run                  # build, install, launch
adb logcat -s fin.android:V AndroidRuntime:E    # the app's logs; a crash is "AndroidRuntime: FATAL"
```

Useful afterwards:

```sh
adb uninstall fin.android          # clean slate: also wipes the stored config and secrets
make reinstall                     # fixes the "signatures do not match" error after a release smoke
adb exec-out screencap -p > shot.png
```

**First launch** asks for a private GitHub repo and a fine-grained PAT (GitHub -> Settings ->
Developer settings -> Personal access tokens -> Fine-grained; *Repository access* = that one repo;
*Permissions -> Contents: Read and write*), plus a passphrase. After that it is biometric unlock.

---

## 4. Run it on an emulator

```sh
make setup-emulator       # one-time: emulator + an API 37 system image + the AVD (~1.5 GB)
make emulator             # boots it headless and waits until it is ready
make run                  # install + launch on it
make emulator-kill        # stop it
```

The AVD is named after the build's `compileSdk` (`test37` today); override with
`make emulator AVD=other`. A visible window instead of headless:
`$ANDROID_HOME/emulator/emulator -avd test37` in its own terminal.

---

## 5. Release (maintainer only)

**The signing key is the one thing here that cannot be replaced.** Android identifies an installed
app by its signature: if you lose the key, no future build can ever upgrade an installed app - users
would have to uninstall (losing the local config) and reinstall. It is therefore never in this repo,
never in a commit, never in a log.

Where it lives:

```sh
# One-time, OUTSIDE the working tree:
keytool -genkeypair -v -keystore "$HOME/finador-release.jks" -alias finador \
    -keyalg RSA -keysize 4096 -validity 10000      # asks for the two passwords
chmod 600 "$HOME/finador-release.jks"

cat >> ~/.gradle/gradle.properties <<'EOF'
FINADOR_STORE_FILE=/absolute/path/to/finador-release.jks
FINADOR_STORE_PASSWORD=<store password>
FINADOR_KEY_ALIAS=finador
FINADOR_KEY_PASSWORD=<key password>
EOF
chmod 600 ~/.gradle/gradle.properties
```

The same four names are read from the **environment** when the properties file has none, so a CI
runner can inject them as secrets. Without them the release build falls back to **debug signing**,
which is fine locally and fatal under a release name (that key is public) - the publishing targets
refuse it.

**Back the keystore up**, offline, in more than one place, with its passwords. That backup is the
app's continuity.

```sh
make check-signing        # is a real key configured on this machine? (prints no secret)
make emulator && make probe-device   # the live providers, through the stack a phone has
# bump versionName + versionCode in app/build.gradle.kts, commit
make gh-release-dry-run   # gates + APK + signature check; tags and publishes NOTHING
make gh-release           # the real thing: tag, push, GitHub release with the APK attached
make gh-release NOTES=notes.md   # hand-written notes instead of GitHub-generated ones
```

**A release owes one green `make probe-device`**, on an emulator or a phone. Not `make probe`: the
host JVM's TLS is the JDK's JSSE and a phone's is Conscrypt, the two send different handshakes, and
a provider that fingerprints handshakes answers them differently. On 2026-09-20 the host probe was
red on every Yahoo call (`429`) while the very same code on the emulator was green, from the same
IP and the same minute. `net/Tls.kt` holds that measurement. The device probe is not part of
`gh-release`, because it needs a booted emulator and the live internet; run it by hand first.

`gh-release` runs `make test` and `make crossimpl`, builds the R8-minified release APK,
verifies its signature with `apksigner`, stages it as
`app/build/dist/finador-android-v<version>.apk` and attaches it to the GitHub release. Re-running is
safe: an existing tag at `HEAD` is reused, an existing release has its asset replaced.

---

## 6. WHEN ANDROID MOVES

Android ships a new version every year, and unlike Go it has no compatibility promise: an API level
can change behaviour for apps that target it, and Google does deprecate and remove. This is the
yearly half-day that keeps the app alive. Nothing here is urgent the day it lands; all of it is
overdue two years later.

### The order to bump things

Each step's tool must support the next, so the order is not negotiable. Run `make test` after each
one; a failure then names its own cause.

1. **Gradle wrapper** - `gradle/wrapper/gradle-wrapper.properties`: the `distributionUrl` **and**
   the `distributionSha256Sum` (from `gradle.org/release-checksums`).
2. **Android Gradle Plugin** - `agp` in `gradle/libs.versions.toml`. Read
   [the AGP/Gradle compatibility table](https://developer.android.com/build/releases/gradle-plugin)
   first: AGP N needs Gradle >= M.
3. **Kotlin** - `kotlin` in the catalog. It drives the Compose compiler and the serialization
   plugin, which share that one version: bump the three together or the build fails on a plugin
   mismatch.
4. **`compileSdk`** - `app/build.gradle.kts`. Compiling against the new platform, still targeting
   the old one: no behaviour change yet, and this is what unblocks the AndroidX libraries.
5. **The AndroidX libraries** - Compose BOM, navigation, lifecycle, core. Several of them
   hard-require the new `compileSdk`, which is why they come after it.
6. **`targetSdk`** - the one that opts the app into the new behaviour. Read the two pages below
   BEFORE this step, then run the whole gate set and a smoke test on an emulator of the new level.

### The two pages to read, every year

- `developer.android.com/about/versions/<N>/behavior-changes-all` - applies to your app whatever it
  targets, i.e. the moment a user gets a new phone.
- `developer.android.com/about/versions/<N>/behavior-changes-<N>` - applies once `targetSdk` is N.

Answer them row by row and write the answers down: `docs/maintainability.md` §8 is last year's
table and the template for the next one. Most rows are "no": this app declares one permission
(`INTERNET`), one activity, no service, no receiver, no background work and no reflection.

### What proves it

```sh
make test        # the whole unit suite
make build
make lint        # must print "No issues found"
make crossimpl   # the .fin file format still matches the Go implementation byte for byte
make setup-emulator && make emulator && make run    # it RUNS on the new API level
make probe-device                                   # the providers still answer THAT platform
```

Compiling against a new platform proves nothing about running on it: the emulator smoke is not
optional.

### Worked example: the September 2026 wave (API 37, Android 17)

What it actually took, in this order, one commit each:

| # | Bump | Note |
|---|---|---|
| 1 | Gradle wrapper 9.5.0 -> **9.7.1** | plus `distributionSha256Sum`, which had never been pinned |
| 2 | AGP 9.3.3 -> **9.4.1** | the first AGP that compiles against API 37; needs Gradle >= 9.6 |
| 3 | `compileSdk` 36 -> **37** | the package is `platforms;android-37.0` - platform packages carry a minor version since Android 17 |
| 4 | Compose BOM 2026.06.01 -> **2026.09.00**, navigation-compose 2.9.8 -> **2.10.1**, lifecycle 2.10.0 -> **2.11.0**, core 1.18.0 -> **1.19.0** | lifecycle 2.11 and the BOM both hard-require `compileSdk` 37 |
| 5 | `targetSdk` 36 -> **37** | both behaviour-change lists audited row by row: **no code change was required** |

What broke, honestly: nothing in the app. What broke around it was the **dependency that gated the
whole wave** - okhttp 5.5.0 refused to build below `compileSdk` 37 - and it was removed rather than
bumped (the platform's `HttpURLConnection` does everything this app asked of it; see
`docs/maintainability.md` §6). Two Lint findings from the previous API level were fixed in the same
pass (`android:statusBarColor`/`navigationBarColor`, deprecated and no-ops since API 35).

### How to read a deprecation notice

Three questions, in this order:

1. **Does it apply to an app that only targets the new level, or to every app?** "All apps" means a
   user's new phone changes behaviour under you, whatever you do. Those are the urgent ones.
2. **What is the removal date?** Android deprecations usually announce a version where the API stops
   working, often two releases later. That date is the deadline, not the notice.
3. **Is the replacement available at `minSdk` 26?** Often it is not (see `androidx.biometric` in
   `docs/maintainability.md` §4), and then the choice is "keep the old API" or "raise `minSdk` and
   drop old phones" - a product decision, to be written down, not a mechanical fix.

A compiler `w:` deprecation warning is a bug in this project: a clean recompile prints none.

### When it stops building, or stops working on a new phone

In this order, because each step rules out the one below:

1. `make doctor` - a missing JDK, SDK platform or licence explains most sudden failures.
2. **The wrapper**: `./gradlew --version`. Did the Gradle distribution change under you, or fail its
   checksum? The distribution is cached in `~/.gradle/wrapper/dists`; delete that folder to refetch.
3. **The compatibility triangle** AGP vs Gradle vs Kotlin. Re-read the AGP release page; a mismatch
   there is what produces a plugin error that names none of the three.
4. **`compileSdk`**: a library that fails with "requires compileSdk 3x" is telling you the whole
   truth. Bump the platform, not the library back.
5. `make lint` - it reports API-level misuse (a call that does not exist at `minSdk` 26) that the
   compiler cannot see.
6. **An emulator on the new API level**: `make setup-emulator && make emulator && make run`. A
   crash there is a real crash on a real new phone.
7. `adb logcat -s fin.android:V AndroidRuntime:E` - filtered on this package. A `FATAL EXCEPTION`
   block names the class and line. If the app only fails on the device and not on the emulator, the
   usual suspects are the venue-specific market data (see `make probe`) and the biometric prompt.

Behaviour changes that could plausibly bite this app one day are listed and answered in
`docs/maintainability.md` §8. The `targetSdk` 37 one to remember: Android 17 turns on **Encrypted
Client Hello** and **Certificate Transparency** by default, which touches the four HTTPS quote
providers. If a provider ever fails on new phones only, that is the first hypothesis, and `make
probe-device` is the test: it is the only one that runs on the phone's own TLS stack.

---

## 7. Layout

```
app/src/main/kotlin/fin/android/
  crypto/  domain/  format/    # pure-Kotlin core (Argon2id/HKDF/AES-GCM, model, .fin read/write)
  net/     remote/  market/    # HTTP transport, GitHub sync, multi-source quotes
  valuation/                   # valuation, performance, gains
  data/    ui/                 # DI + repository, Compose screens
app/src/test/kotlin/           # the unit suite (JVM) + FakeHttpServer
app/src/probe/kotlin/          # the live provider probe, compiled into BOTH test source sets
app/src/androidTest/kotlin/    # the one instrumented test: that probe, on a device (make probe-device)
scripts/doctor.sh setup.sh     # the dev environment
scripts/crossimpl.sh           # bidirectional compatibility test against the Go binary
gradle/libs.versions.toml      # every dependency, at an exact version
Makefile                       # every command (`make help`)
```

- **`AGENTS.md`** - the architecture map, the invariants, the gotchas. Read it before changing code
  (it is written for AI agents, and it is the densest description of this codebase).
- **`docs/maintainability.md`** - the dependency ledger: what each library is for, who maintains it,
  what would break if it died, and what the yearly upkeep really costs.
- The file format and sync model are specified on the finador side: `../finador/docs/FORMAT.md` and
  `../finador/docs/superpowers/specs/2026-06-13-github-remote-data-design.md`.
