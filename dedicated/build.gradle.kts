import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The standalone dedicated server.
 *
 * A module of its own rather than a `main()` inside :server, because this is
 * the only part of the project Android never loads - and that freedom is load
 * bearing. Unix domain sockets, used for the admin control channel, arrived in
 * JDK 16 and do not exist on Android at all. Keeping them here means :server
 * can stay on the JVM 11 target that :app consumes, while this targets 21.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

application {
    mainClass.set("com.rm.apogee.dedicated.MainKt")
    applicationName = "apogee-server"
}

dependencies {
    implementation(project(":server"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
