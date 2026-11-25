#!/usr/bin/env bash
# Verifies the install path a new user would take: publish to mavenLocal, then build a brand-new
# project (outside this repo) that depends only on the published artifacts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
VERSION="$(grep '^VERSION_NAME=' "$ROOT/gradle.properties" | cut -d= -f2)"
GROUP="$(grep '^GROUP=' "$ROOT/gradle.properties" | cut -d= -f2)"

echo "Publishing $GROUP:*:$VERSION to mavenLocal..."
(cd "$ROOT" && ./gradlew publishToMavenLocal -Psynclab.native="${SYNCLAB_NATIVE:-false}" -q)

echo "Creating a fresh project in $WORK"
mkdir -p "$WORK/src/test/kotlin"
cp -R "$ROOT/gradle" "$ROOT/gradlew" "$WORK/"
rm -f "$WORK/gradle/libs.versions.toml"
cat > "$WORK/settings.gradle.kts" <<KTS
rootProject.name = "fresh-user"
KTS
cat > "$WORK/build.gradle.kts" <<KTS
plugins { kotlin("jvm") version "2.3.0" }
repositories { mavenLocal(); mavenCentral() }
dependencies {
    testImplementation("$GROUP:synclab-core:$VERSION")
    testImplementation("$GROUP:synclab-junit5:$VERSION")
    testRuntimeOnly("$GROUP:synclab-report:$VERSION")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
KTS
cat > "$WORK/src/test/kotlin/FirstTest.kt" <<'KT'
import dev.synclab.*
import dev.synclab.junit5.SyncLabTest
import kotlinx.coroutines.delay
import kotlin.test.assertFailsWith

class FirstTest {
    private suspend fun Simulation.counter(idempotent: Boolean) {
        var total = 0
        val seen = HashSet<Int>()
        node("server") { serve<Int, Unit> { _, id -> if (!idempotent || seen.add(id)) total++ } }
        val phone = node("phone") { rpc<Int, Unit>("server") }
        var taps = 0
        eventuallyEqual("every tap counted once", { taps }, { total })
        repeat(random.nextInt(1, 15)) { i ->
            taps++
            phone.perform("tap") { while (true) try { invoke(i); break } catch (e: SimulatedNetworkException) { delay(100) } }
        }
    }

    @SyncLabTest fun `naive counter is caught`() { assertFailsWith<SimulationFailure> { explore(runs = 300) { counter(false) } } }
    @SyncLabTest fun `idempotent counter passes`() { explore(runs = 300) { counter(true) } }
}
KT
(cd "$WORK" && ./gradlew test -q)
test -n "$(ls "$WORK"/build/synclab-reports/failures/*.html 2>/dev/null)" || { echo "No HTML report was written"; exit 1; }
echo "Fresh install OK: tests passed, report written to $WORK/build/synclab-reports/failures/"
