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
        versionCode = 12
        versionName = "1.2.9-v1-premium-realcar-beta"
        buildConfigField("String", "SOURCE_COMMIT", "\"" + (System.getenv("GITHUB_SHA") ?: "LOCAL") + "\"")
        buildConfigField("boolean", "VOICE_PREVIEW_BUILD", "false")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        create("voicePreview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".voicepreview"
            versionNameSuffix = "-voice-preview"
            buildConfigField("boolean", "VOICE_PREVIEW_BUILD", "true")
            matchingFallbacks += listOf("release")
        }
    }

    dependencies {
        // In-process ADB client; V1 generates/owns its auth key. No reference APK keys are copied.
        implementation("dev.mobile:dadb:1.2.9")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


// CI trigger: runtime surface drift enforcement

// CI trigger: vehicle prompt runtime validation
