plugins {
    id("synclab.jvm-library")
}

description = "SyncLab for JUnit 5: exact test names in reports and replay commands."

dependencies {
    api(project(":synclab-core"))
    api(libs.junit.api)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}

mavenPublishing {
    coordinates(artifactId = "synclab-junit5")
    pom { name.set("SyncLab JUnit 5"); description.set(project.description) }
}
