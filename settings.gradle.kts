pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // google() is listed last: it is only needed for AGP (":app"), and it is
        // unreachable in SDK-less CI containers where only ":rfb" is built.
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        // JediTerm (the desktop SSH terminal) is published only here.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

rootProject.name = "mac-remote"

include(":rfb")
include(":shared")
include(":desktop")

// ":app" needs the Android SDK and AGP (both served from dl.google.com). Include it
// only where an SDK exists so `gradle :rfb:test` still works in plain-JVM CI.
val localProps = file("local.properties")
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))
if (hasAndroidSdk) {
    include(":app")
}
