plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.koala.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.koala.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 8
        versionName = "1.4.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/koalatv.jks")
            storePassword = "koalatv2026"
            keyAlias = "koalatv"
            keyPassword = "koalatv2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // Décodeur audio logiciel (AC3, E-AC3, DTS, TrueHD…) pour les appareils qui ne les lisent pas
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("io.coil-kt:coil:2.7.0")
}
