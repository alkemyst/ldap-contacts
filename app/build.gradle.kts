plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.ldapcontacts"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.ldapcontacts"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.1"
    }

    signingConfigs {
        // Fixed debug key, committed on purpose: APKs built by CI then install over
        // each other. Debug builds only; release builds are signed elsewhere.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // No build-type configuration: the server is a runtime setting, not a
        // build-time one, and there is no backend.
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // LDAPS client. Adds roughly 3 MB to the APK; no AndroidX, no transitive deps.
    implementation("com.unboundid:unboundid-ldapsdk:7.0.1")
    // QR code scanner for the first-run configuration.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Its CaptureManager calls ContextCompat and ActivityCompat to ask for the camera
    // permission, but its POM declares no AndroidX dependency: without this the
    // scanner dies with NoClassDefFoundError the moment it starts, and nothing in the
    // build catches it, since our own code never touches those classes.
    implementation("androidx.core:core:1.13.1")
}
