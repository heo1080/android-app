plugins {
    id("com.android.application")
}

android {
    namespace = "com.dolphin.launcher.v1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dolphin.launcher.v1"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1-v1-byd-install-hotfix"
        buildConfigField("String", "SOURCE_COMMIT", "\"" + (System.getenv("GITHUB_SHA") ?: "LOCAL") + "\"")
    }

    buildFeatures {
        buildConfig = true
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
