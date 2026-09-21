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

/**
 * Runs the headless ascent scenario: `./gradlew :core:flyAscent`.
 *
 * The physics iteration loop. A full flight to orbit runs in well under a
 * second here, against minutes to rebuild, install and fly on a device.
 */
tasks.register<JavaExec>("flyAscent") {
    group = "verification"
    description = "Flies the stock rocket to orbit headlessly and prints telemetry."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.core.scenario.AscentScenarioKt")
}

tasks.register<JavaExec>("padDiagnostic") {
    group = "verification"
    description = "Probe: behaviour of an unpowered craft resting on the launch pad."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.core.scenario.PadDiagnosticKt")
}
