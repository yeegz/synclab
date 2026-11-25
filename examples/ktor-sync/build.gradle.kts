plugins {
    id("synclab.example")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    testImplementation(project(":synclab-ktor"))
    testImplementation(project(":synclab-junit5"))
    testRuntimeOnly(project(":synclab-report"))
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
