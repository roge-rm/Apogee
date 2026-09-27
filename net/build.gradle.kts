import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: the protocol, codec and transports. There are no Android APIs, so the same
// TcpTransport drives a phone acting as host and the dedicated server.
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
    api(project(":core"))
    api(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
