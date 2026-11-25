plugins {
    id("synclab.kmp-library")
}

description = "SyncLab for Ktor: route a real Ktor HttpClient through the simulated network."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":synclab-core"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.mock)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mavenPublishing {
    coordinates(artifactId = "synclab-ktor")
    pom { name.set("SyncLab Ktor"); description.set(project.description) }
}
