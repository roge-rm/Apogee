import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The standalone dedicated server.
 *
 * It's a module of its own instead of a `main()` inside :server, because this is the only part of
 * the project Android never loads, and a lot rests on that. Unix domain sockets, used for the admin
 * control channel, arrived in JDK 16 and don't exist on Android at all. Keeping them here means
 * :server can stay on the JVM 11 target that :app uses, while this one targets 21.
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
