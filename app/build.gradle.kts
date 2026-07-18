import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fin.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "fin.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Real release signing, configured entirely outside the repo: the keystore path and passwords
    // come from ~/.gradle/gradle.properties (FINADOR_STORE_FILE / FINADOR_STORE_PASSWORD /
    // FINADOR_KEY_ALIAS / FINADOR_KEY_PASSWORD), which is never committed. When absent (other
    // contributors / CI) the values are null and the release build falls back to debug signing,
    // so the repo still builds for everyone without exposing any secret.
    val releaseStoreFile = (findProperty("FINADOR_STORE_FILE") as String?)?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = findProperty("FINADOR_STORE_PASSWORD") as String?
                keyAlias = findProperty("FINADOR_KEY_ALIAS") as String?
                keyPassword = findProperty("FINADOR_KEY_PASSWORD") as String?
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
