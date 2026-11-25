rootProject.name = "synclab"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Published libraries
include(":synclab-core")
include(":synclab-ktor")
include(":synclab-report")
include(":synclab-junit5")

// Not published: realistic integrations, benchmarks
include(":examples:offline-tasks")
include(":examples:expense-ledger")
include(":examples:collab-text")
include(":examples:ktor-sync")
include(":benchmarks")
