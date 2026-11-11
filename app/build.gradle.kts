plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.aria"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aria"
        // minSdk 26 (Android 8.0) — chosen deliberately:
        //  - it covers the vast majority of devices in use today
        //  - it keeps audio/sound-mode APIs simple (AudioManager APIs used in
        //    later phases behave consistently from API 26 onward)
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Phase 1 keeps release unminified so that a future release build
            // is easy to debug. R8/shrinking is a Phase 16 concern.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Dispatchers.setMain/resetMain reach into android.os.Looper. Returning
        // default values for those stubs keeps the Phase 2 unit tests pure JVM —
        // no Robolectric, no device, no extra dependency.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android + lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose (BOM keeps all Compose artifact versions aligned)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Small core icon set for the settings gear. Declared explicitly because
    // it no longer arrives transitively from material3; the heavy "extended"
    // icon artifact stays out on purpose (project rule, see PROJECT_GUIDE §4).
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Unit tests (run on your computer, no device needed)
    testImplementation(libs.junit)
    // Phase 2: Dispatchers.setMain so the ViewModel's speech flow is testable
    // on the JVM (see MainDispatcherRule). Test-only, adds nothing to the APK.
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented UI tests (run on a device/emulator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
