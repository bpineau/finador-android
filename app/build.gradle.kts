import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fin.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "fin.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "0.1.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Real release signing, configured entirely outside the repo. The keystore path and its
    // passwords come from FINADOR_STORE_FILE / FINADOR_STORE_PASSWORD / FINADOR_KEY_ALIAS /
    // FINADOR_KEY_PASSWORD, read from ~/.gradle/gradle.properties (never committed) or, failing
    // that, from the environment - so a CI runner can inject them as secrets without writing a
    // file anywhere. Nothing about the key may ever live in the repo: not the keystore, not a
    // password, not a path inside the working tree.
    //
    // When they are absent (other contributors, a plain CI build) the release build falls back
    // to debug signing, so the repo still builds for everyone. That fallback is a convenience
    // for local builds only: `make gh-release` refuses to publish a debug-signed APK.
    fun signingSecret(name: String): String? =
        ((findProperty(name) as String?) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

    val releaseStoreFile = signingSecret("FINADOR_STORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingSecret("FINADOR_STORE_PASSWORD")
                keyAlias = signingSecret("FINADOR_KEY_ALIAS")
                keyPassword = signingSecret("FINADOR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the real release key when configured (see signingConfigs above); otherwise fall
            // back to debug signing so the repo still builds for contributors/CI without the keystore.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME is shown in Settings
    }

    // The live provider probe (`market/LiveProbe.kt`) is compiled into BOTH test source sets rather
    // than duplicated: the host JVM and a device do not share a TLS stack, so the same body has to
    // run in both places to mean anything (see `net/Tls.kt`). It ships in neither APK, debug or
    // release: `src/probe` is not part of `main`.
    sourceSets {
        getByName("test").kotlin.srcDir("src/probe/kotlin")
        getByName("androidTest").kotlin.srcDir("src/probe/kotlin")
    }
}

// The JDK the build RUNS on, pinned here instead of inherited from whatever the laptop happens to
// have on its PATH: Gradle picks an installed JDK 21 for javac, kotlinc and the forked test JVM, so
// two machines compile the same bytecode. `make doctor` checks that such a JDK exists and
// `make setup` installs it (Homebrew's `temurin@21`). Auto-provisioning is deliberately OFF (see
// `gradle.properties`): the only way to turn it on is a third-party resolver plugin that downloads
// JDKs from a third-party API at build time, which is one more moving part than this project wants
// for something a package manager already does.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        // The BYTECODE level, a different question from the JDK above: Android's D8 reads Java 17
        // class files. Keep it in step with `compileOptions`, or Gradle fails the build with an
        // inconsistent-JVM-target error.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Forward the opt-in test properties from the Gradle JVM into the forked test JVM: the
// cross-implementation vectors (scripts/crossimpl.sh) and the live provider probe (`make probe`).
tasks.withType<Test>().configureEach {
    listOf("crossimpl.out", "crossimpl.go.file", "crossimpl.go.pw", "probe").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    // Declared, not inherited: ui/Theme.kt (WindowCompat), ui/UnlockScreen.kt
    // (ContextCompat.getMainExecutor) and data/SecretStore.kt (SharedPreferences.edit) all use it,
    // and it would otherwise arrive only as a transitive of Compose.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit)
    // The ONE instrumented test is the live provider probe (`make probe-device`), the only check
    // that can speak for the TLS stack a phone actually has. androidx.test:runner is what supplies
    // the AndroidJUnitRunner named in defaultConfig; it is first-party, test-only, and reaches no
    // APK. Nothing else is needed: the probe touches no Context, so `androidx.test.ext:junit` and
    // its InstrumentationRegistry stay out.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
