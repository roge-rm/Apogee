import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The game server that's in charge, as a library.
//
// It's a library and nothing else. :app embeds it to host a game in-process, and :dedicated wraps
// it in a standalone process. Neither one is special, and there's exactly one version of the
// simulation between them.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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

dependencies {
    api(project(":net"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Connects to a running host as a real client: `./gradlew :server:netProbe`.
 *
 * It closes the loop no unit test can: a game hosted from a real device, joined from a real second
 * process, over a real socket.
 */
tasks.register<JavaExec>("netProbe") {
    group = "verification"
    description = "Joins a running Apogee host and reports what it sees."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.server.NetProbeKt")
}
