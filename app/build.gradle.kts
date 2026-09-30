plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.vellum"
    compileSdk = 35

    // Each GitHub build gets a higher version number, so Android treats it as an update.
    val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "app.vellum"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // One fixed key for every build, so new versions install over the old one.
    // Fine for personal sideloading; use a private key (kept out of git) before publishing on the Play Store.
    signingConfigs {
        create("vellum") {
            storeFile = rootProject.file("keystore/vellum.jks")
            storePassword = "vellum-sideload"
            keyAlias = "vellum"
            keyPassword = "vellum-sideload"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("vellum")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("vellum")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // PDF editing engine (Android port of Apache PDFBox)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
