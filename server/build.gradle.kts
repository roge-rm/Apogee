import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The authoritative game server. Built as a library so :app can host a game
// in-process, and with the `application` plugin so the same code also runs
// standalone as the dedicated server (`./gradlew :server:run`).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

application {
    mainClass.set("com.rm.apogee.server.DedicatedServerKt")
}

dependencies {
    api(project(":net"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Connects to a running host as a real client: `./gradlew :server:netProbe`.
 *
 * Closes the loop no unit test can - a game hosted from a real device, joined
 * from a real second process, over a real socket.
 */
tasks.register<JavaExec>("netProbe") {
    group = "verification"
    description = "Joins a running Apogee host and reports what it sees."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.server.NetProbeKt")
}
