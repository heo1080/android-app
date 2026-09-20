plugins {
    id("com.android.application")
}

android {
    namespace = "com.dolphin.launcher.v1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dolphin.launcher.v1"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "1.0.0-v1-evolution"
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
}
