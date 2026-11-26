plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tilecam"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tilecam"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes from the environment (CI secrets); without it the release APK is unsigned.
    val keystore = System.getenv("TILECAM_KEYSTORE")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("TILECAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TILECAM_KEY_ALIAS")
                keyPassword = System.getenv("TILECAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // Prebuilt OpenCV for Android (Java bindings + native libs) from Maven Central.
    implementation("org.opencv:opencv:4.10.0")
}
