plugins {
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.bcv)
    id("org.jetbrains.dokka")
}

apiValidation {
    ignoredProjects += listOf("offline-tasks", "expense-ledger", "collab-text", "ktor-sync", "benchmarks")
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib { enabled = false }
}

dependencies {
    dokka(project(":synclab-core"))
    dokka(project(":synclab-ktor"))
    dokka(project(":synclab-report"))
    dokka(project(":synclab-junit5"))
}

dokka {
    moduleName.set("SyncLab")
}
