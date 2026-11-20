plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val youtubedlAndroid = "0.17.4"

android {
    namespace = "com.swiftload.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swiftload.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // One APK per phone CPU type keeps the file small (yt-dlp + Python + FFmpeg are bundled).
    // Most modern phones need the "arm64-v8a" APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK installs straight away.
            // Replace with your own keystore before publishing on Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Required by youtubedl-android: native binaries must be extracted on install.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // yt-dlp engine (YouTube, TikTok, Instagram, Facebook, X, 1000+ sites) + FFmpeg for MP3/merging
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
}
