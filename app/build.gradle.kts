import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties (git-ignored; see docs/RELEASE.md).
// Without it, release builds are signed with the debug key so they can still be sideloaded.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "app.ipcamhub"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.ipcamhub"
        minSdk = 26
        targetSdk = 36
        // versionCode = major * 10000 + minor * 100 + patch (1.0.0 = 10000). Play needs it higher on every upload.
        versionCode = 10000
        versionName = "1.0.0"
        ndk {
            // The UVC library ships native code for four ABIs. Keep real phones and the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // "full" includes Chromecast (Google Play services, not free software); "foss" leaves it out for
    // F-Droid and IzzyOnDroid. Everything else is the same.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.datastore)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.androidx.lifecycle.process)
    "fullImplementation"(libs.play.services.cast)
    implementation(libs.nanohttpd)
    implementation(libs.uvc.android)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
