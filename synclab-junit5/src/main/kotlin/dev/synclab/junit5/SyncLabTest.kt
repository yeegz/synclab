package dev.synclab.junit5

import dev.synclab.internal.TestNameHolder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Marks a JUnit 5 test that runs SyncLab simulations. Equivalent to `@Test` plus [SyncLabExtension],
 * which gives reports and replay commands the exact test name (including names with spaces).
 *
 * ```
 * @SyncLabTest
 * fun `ledger converges`() = explore(runs = 200) { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Test
@ExtendWith(SyncLabExtension::class)
public annotation class SyncLabTest

/**
 * Records the current test's class and method so SyncLab can print a precise
 * `./gradlew test --tests "…"` replay command and name report files after the test.
 * Apply it with `@ExtendWith(SyncLabExtension::class)` or use [SyncLabTest].
 */
public class SyncLabExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        val cls = context.requiredTestClass.name
        val method = context.requiredTestMethod.name
        TestNameHolder.current.set("$cls.$method")
    }

    override fun afterEach(context: ExtensionContext) {
        TestNameHolder.current.remove()
    }
}
