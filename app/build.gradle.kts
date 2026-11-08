import java.util.Properties

plugins {
    id("com.android.application")
}

// Release-Signatur: Zugangsdaten liegen in keystore.properties (nicht im Git!).
// Fehlt die Datei, kann trotzdem eine Debug-APK gebaut werden.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.phoenix153.mytube"
    compileSdk {
        version = release(37) { minorApiLevel = 2 } // von GeckoView/androidx.core verlangt
    }

    defaultConfig {
        applicationId = "io.github.phoenix153.mytube"
        minSdk = 26
        targetSdk = 36
        // Bei jedem Release beide Werte erhöhen. versionName muss zum GitHub-Tag passen (v1.0.0).
        versionCode = 1
        versionName = "1.0.0"

        // Nur 64-Bit-ARM: deckt alle aktuellen Handys ab und hält die APK klein
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Native Bibliotheken komprimiert ins APK packen: halbiert etwa die Downloadgröße
    // (wichtig beim Weitergeben), kostet nur einmalig etwas Zeit bei der Installation.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Browser-Engine. Neue Versionen: https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:159.0.20261102144711")
    implementation("androidx.activity:activity-ktx:1.12.4")
}
