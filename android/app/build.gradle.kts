plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.linkphone.app"
    compileSdk = 35

    // Release builds use a persistent key supplied by GitHub Actions. v0.1 was
    // signed by an ephemeral runner debug key; from v0.2 onward APK updates keep
    // the same signature.
    val releaseKeystorePath = System.getenv("LINKPHONE_KEYSTORE_PATH")
    val releaseSigning = if (!releaseKeystorePath.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystorePath)
            storePassword = System.getenv("LINKPHONE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LINKPHONE_KEY_ALIAS")
            keyPassword = System.getenv("LINKPHONE_KEY_PASSWORD")
        }
    } else {
        null
    }

    defaultConfig {
        applicationId = "dev.linkphone.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = releaseSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.zxing:core:3.5.3")
}
