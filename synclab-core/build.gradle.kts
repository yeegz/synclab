plugins {
    id("synclab.kmp-library")
}

description = "SyncLab core: deterministic simulation engine, simulated network, invariants, replay and shrinking."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mavenPublishing {
    coordinates(artifactId = "synclab-core")
    pom { name.set("SyncLab Core") ; description.set(project.description) }
}
