plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rewen.sonarbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rewen.sonarbridge"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-dev"
    }

    // Pinned so containerized builds don't regenerate a fresh keystore each run
    // (which breaks `adb install -r` with UPDATE_INCOMPATIBLE).
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
}
