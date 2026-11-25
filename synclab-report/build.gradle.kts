plugins {
    id("synclab.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

description = "SyncLab reports: machine-readable JSON for CI and a self-contained HTML trace viewer."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":synclab-core"))
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mavenPublishing {
    coordinates(artifactId = "synclab-report")
    pom { name.set("SyncLab Report"); description.set(project.description) }
}
