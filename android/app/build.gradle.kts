plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kpi.salesapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kpi.salesapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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

    // Cho phép build APK không ký (debug) để GitHub Actions xuất file cài trực tiếp.
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Đăng nhập Google
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Gọi API (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines (chạy mạng nền, không đơ UI)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
