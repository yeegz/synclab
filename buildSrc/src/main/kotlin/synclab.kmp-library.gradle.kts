// Convention for every published SyncLab multiplatform module.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

// Native targets need the Kotlin/Native toolchain (~1 GB first download).
// They are on by default; pass -Psynclab.native=false for a fast JVM + JS loop.
val enableNative = (findProperty("synclab.native") as String?)?.toBoolean() ?: true

extensions.configure<KotlinMultiplatformExtension> {
    explicitApi()
    jvmToolchain(17)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    js {
        nodejs { testTask { useMocha { timeout = "60s" } } }
    }
    if (enableNative) {
        macosArm64()
        iosArm64()
        iosSimulatorArm64()
        linuxX64()
    }
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

mavenPublishing {
    publishToMavenCentral()
    // Signing is only required when actually releasing; see RELEASING.md.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    pom {
        inceptionYear.set("2026")
    }
}
