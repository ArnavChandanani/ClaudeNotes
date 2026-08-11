plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.booxnotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.booxnotes"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Display-side Onyx SDK only (EpdController). NOT the pen/raw-drawing SDK.
    implementation("com.onyx.android.sdk:onyxsdk-device:1.2.31")
}
