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
        versionCode = 5
        versionName = "1.2.2-v1-auto-evidence-beta"
        buildConfigField("String", "SOURCE_COMMIT", "\"" + (System.getenv("GITHUB_SHA") ?: "LOCAL") + "\"")
        val evidenceKey = System.getenv("DOLPHIN_UPLOAD_KEY") ?: ""
        buildConfigField("String", "EVIDENCE_UPLOAD_KEY", "\"" + evidenceKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
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


// CI trigger: runtime surface drift enforcement

// CI trigger: vehicle prompt runtime validation
