import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM. Deliberately has NO Android dependency: this module is the
// whole simulation, and it has to run unchanged inside the app, inside the
// dedicated server, and inside a plain JUnit test.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    // JVM 11 across every shared module so :app (compileOptions 11) can consume them.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
