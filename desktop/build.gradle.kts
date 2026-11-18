import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Windows desktop app (Compose Multiplatform for Desktop). Shares the RFB
// implementation (":rfb") and the profile/SSH code (":shared") with Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
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
    implementation(project(":rfb"))
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    // SSH terminal widget (Swing), LGPL-3.0.
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    // Windows DPAPI for saved passwords.
    implementation(libs.jna.platform)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "io.github.halz.macremote.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "MacRemote"
            packageVersion = "1.0.0"
            description = "Mac / Windows remote desktop client"
            vendor = "halz"
            // jlink keeps only these JDK modules (see :desktop:suggestRuntimeModules);
            // jdk.crypto.ec is needed for SSH key exchange, jdk.charsets for
            // non-UTF-8 terminal output.
            modules("java.naming", "java.management", "jdk.crypto.ec", "jdk.unsupported", "jdk.charsets")
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "Mac Remote"
                shortcut = true
                dirChooser = true
                // Installs under the user profile: no administrator rights needed.
                perUserInstall = true
                // Fixed so a newer installer upgrades an older install in place.
                upgradeUuid = "4b0f7c3e-2a51-4d7e-9a5c-8f1e6d2b9c40"
            }
        }
    }
}
