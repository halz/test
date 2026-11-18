// Pure-JVM code shared by the Android app (":app") and the Windows desktop
// app (":desktop"): the connection profile model and the SSH helpers.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.jsch)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
