plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.dynamicsolutions.sonarbridge"
    compileSdk = 36
    // build-tools stays 35.0.0: the pinned arm64 aapt2 static build (see
    // docker/Dockerfile.arm64) only goes up to 35.0.2, and the rest of
    // build-tools is Java inside AGP.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "ca.dynamicsolutions.sonarbridge"
        minSdk = 29
        // Keep dependency-owned controls within the same language coverage as the app.
        resourceConfigurations += listOf("en", "en-rCA", "es", "fr", "fr-rCA", "de", "b+zh+Hans", "pt", "ja")
        targetSdk = 36
        // CI (release.yml) injects the tag version; local builds stay 0.1-dev
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "0.1-dev"
    }

    // Two distribution channels from one codebase:
    //  - github: development and the final legacy migration APK
    //  - play:   public APK on both channels, no self-update, softer battery-opt
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "IS_PLAY", "false")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "IS_PLAY", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Debug pinned so containerized builds don't regenerate a fresh keystore
    // each run (breaks `adb install -r`); release key comes from env/CI
    // secrets (android/keystore/release.keystore locally, gitignored).
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ks = System.getenv("ANDROID_KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("ANDROID_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Android resource/lifecycle checks span three framework sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
