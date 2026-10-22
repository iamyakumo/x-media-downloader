plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yakumo.xvideo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yakumo.xvideo"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/xvideo-release.jks") // optional local keystore; not in repo
            storePassword = System.getenv("XVIDEO_STORE_PASSWORD") ?: ""
            keyAlias = "xvideo"
            keyPassword = System.getenv("XVIDEO_KEY_PASSWORD") ?: ""
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
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
