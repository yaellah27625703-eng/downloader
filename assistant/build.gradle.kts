// The assistant is its own Gradle build (newer Kotlin than the downloader app), built with: ./gradlew -p assistant assembleRelease
plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.4.0"
}

android {
    namespace = "com.vadila.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vadila.ai"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../app/release.jks")
            storePassword = "vadila2026"
            keyAlias = "downloader"
            keyPassword = "vadila2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                "META-INF/versions/**", "META-INF/INDEX.LIST")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // streaming answers (server-sent events) and live voice calls (WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // free on-device background removal (Google ML Kit)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // offline AI inside the phone (Gemma 4 via Google's LiteRT-LM)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
