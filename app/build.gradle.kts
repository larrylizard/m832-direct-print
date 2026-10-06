import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.m832print.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.m832print.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 17 // bump this on every real change, alongside versionName below
        versionName = "1.16"

        // Build timestamp, independent of whether versionCode/Name got bumped --
        // the one thing guaranteed to differ on every single build, so the app
        // can always prove which exact build is actually installed. Added
        // 2026-10-05 at the user's request after several same-day rebuilds made
        // it hard to be sure which APK was actually running on the phone.
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
