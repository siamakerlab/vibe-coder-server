pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// Auto-provision JDK 21 toolchain via Foojay (Adoptium) when not present locally.
// Required because AGP 9 / Kotlin 2.2 jvmToolchain(21) needs a real JDK 21 for javac.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vibe-coder"

include(":shared")
include(":server")
include(":android-app:app")

project(":android-app:app").projectDir = file("android-app/app")
