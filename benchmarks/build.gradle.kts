plugins {
    id("synclab.example")
    application
}

dependencies {
    implementation(project(":synclab-core"))
    implementation(libs.coroutines.test)
}

application { mainClass.set("dev.synclab.bench.BenchmarksKt") }
