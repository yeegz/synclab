plugins { id("synclab.example") }

dependencies {
    testImplementation(project(":synclab-core"))
    testImplementation(project(":synclab-junit5"))
    testRuntimeOnly(project(":synclab-report"))
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
