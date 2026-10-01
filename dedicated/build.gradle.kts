import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The standalone dedicated server. It's its own module because Android never loads it: the admin
 * channel uses Unix domain sockets (JDK 16+, not on Android), so this targets 21 while :server
 * stays on JVM 11 with :app.
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
