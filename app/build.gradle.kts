plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "az.mursal.claudeusage"
    compileSdk = 35

    defaultConfig {
        applicationId = "az.mursal.claudeusage"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Only dependency: reliable background refresh (also pulls in kotlinx-coroutines).
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
