// Convention for the example integrations: plain JVM, never published.
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
