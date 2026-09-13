pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "audio-io-kmp"

include(":audio-io-kmp")
include(":examples:audio-visualizer")

// Android application that packages the visualizer: it hosts the SDL activity
// and the per-ABI libmain.so the example module links.
include(":examples:audio-visualizer:android")
