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
        targetSdk = 36
        versionCode = 14
        versionName = "0.1.13"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Forward cross-implementation test properties from the Gradle JVM into the forked test JVM.
tasks.withType<Test>().configureEach {
    listOf("crossimpl.out", "crossimpl.go.file", "crossimpl.go.pw").forEach { key ->
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
    implementation(libs.okhttp)
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
