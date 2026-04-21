plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lao.translator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lao.translator"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        aaptOptions {
            noCompress += listOf("bin")
        }

        buildTypes {
            release {
                isMinifyEnabled = false
                isShrinkResources = false
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "25.2.9519653"
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // ✅ ML Kit 离线翻译（模型下载后完全离线工作）
    // 首次使用需下载语言模型（约30MB），之后纯本地
    implementation("com.google.mlkit:translate:17.0.2")
}
