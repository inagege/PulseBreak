plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.breakreminder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.breakreminder"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // Jetpack Compose aktivieren
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Auf deine aktuelle Compose-Version achten
        kotlinCompilerExtensionVersion = "1.4.3"
    }
}

dependencies {
    // -----------------------------------
    // Compose BOM (Bill of Materials)
    // -----------------------------------
    implementation(platform(libs.compose.bom))

    //Swipe with Discrete Screens
    implementation("com.google.accompanist:accompanist-pager:0.20.0")

    // Basis-Bibliotheken für Compose
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)

    // Foundation und Material (hier sind Scaffold, Button, Text usw. enthalten)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation("com.github.skydoves:landscapist-glide:2.1.8")


    implementation("androidx.media:media:1.6.0")


    // Für Icons (z. B. Icons.Filled.*)
    implementation(libs.material.icons.extended)

    // Compose Activity und Navigation
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    // Splashscreen
    implementation(libs.core.splashscreen)

    // -----------------------------------
    // Wear OS (optional, falls benötigt)
    // -----------------------------------
    // Beispiel: Wear Compose Foundation
    implementation(libs.compose.foundation.v110)
    // Für Wear OS: Google Play Services
    implementation(libs.play.services.wearable)
    // Wear-Tooling-Preview
    implementation(libs.wear.tooling.preview)
    implementation(libs.material3.android)
    implementation(libs.navigation.compose)

    // -----------------------------------
    // Tests
    // -----------------------------------
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
