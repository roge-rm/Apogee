// AGP 9 supplies Kotlin support itself, so there is no kotlin-android plugin
// here - only the separate Compose compiler plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rm.apogee"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rm.apogee"
        // Held at 27 deliberately. API level gates none of the rendering
        // capability we need (GLES 3.1/3.2 are a driver capability, queried at
        // runtime), and the modern performance APIs are all reachable behind
        // SDK_INT checks - see platform/PerfHints.kt. The price is that we own
        // a genuine low-end quality tier; see render/QualityTier.kt.
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which gates the on-screen frame/sim timing overlay.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":net"))
    implementation(project(":server"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
