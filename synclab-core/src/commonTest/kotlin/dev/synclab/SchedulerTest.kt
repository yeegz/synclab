package dev.synclab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val quiet = SimConfig { network = NetworkConditions.Perfect; verifyDeterminism = false }

class SchedulerTest {
    @Test
    fun delayUsesVirtualTimeAndRunsInstantly() {
        val result = simulate(config = quiet) {
            sleep(3.hours)
            assertEquals(3.hours, now)
        }
        assertEquals(3.hours.inWholeMilliseconds, result.stats.virtualMillis)
        assertTrue(result.stats.wallMillis < 5_000, "3 virtual hours should take well under 5 s of real time")
    }

    @Test
    fun eventsAtDifferentTimesRunInTimeOrder() {
        val order = mutableListOf<Int>()
        simulate(config = quiet) {
            coroutineScope {
                launch { delay(30); order += 3 }
                launch { delay(10); order += 1 }
                launch { delay(20); order += 2 }
            }
        }
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun withTimeoutUsesVirtualTime() {
        simulate(config = quiet) {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) { delay(2.seconds) }
            }
            assertEquals(1.seconds, now)
        }
    }

    @Test
    fun sameInstantOrderIsShuffledButReproducible() {
        fun orderFor(seed: Long): List<Int> {
            val order = mutableListOf<Int>()
            simulate(seed = seed, config = quiet) {
                coroutineScope { repeat(8) { i -> launch { order += i } } }
            }
            return order
        }
        val a = orderFor(7)
        assertEquals(a, orderFor(7), "same seed must give the same interleaving")
        val distinct = (1L..20L).map { orderFor(it) }.toSet()
        assertTrue(distinct.size > 5, "different seeds should explore different interleavings, got ${distinct.size}")
    }

    @Test
    fun interleavingOffGivesFifo() {
        val order = mutableListOf<Int>()
        simulate(seed = 3, config = quiet.copy { disable(FaultType.INTERLEAVING) }) {
            coroutineScope { repeat(8) { i -> launch { order += i } } }
        }
        assertEquals((0 until 8).toList(), order)
    }

    @Test
    fun yieldLetsOthersRun() {
        val log = mutableListOf<String>()
        simulate(config = quiet.copy { disable(FaultType.INTERLEAVING) }) {
            coroutineScope {
                launch { log += "a1"; yield(); log += "a2" }
                launch { log += "b1"; yield(); log += "b2" }
            }
        }
        assertEquals(listOf("a1", "b1", "a2", "b2"), log)
    }

    @Test
    fun stuckScenarioIsReportedNotHung() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = quiet) {
                CompletableDeferred<Unit>().await()
            }
        }
        assertEquals(ViolationKind.STUCK, failure.report.violation.kind)
    }

    @Test
    fun endlessLoopHitsTheVirtualTimeBudget() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = quiet.copy { maxVirtualTime = 10.seconds }) {
                while (true) delay(100.milliseconds)
            }
        }
        assertEquals(ViolationKind.BUDGET, failure.report.violation.kind)
    }

    @Test
    fun busyLoopHitsTheStepBudget() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = quiet.copy { maxSteps = 10_000 }) {
                while (true) yield()
            }
        }
        assertEquals(ViolationKind.BUDGET, failure.report.violation.kind)
    }

    @Test
    fun backgroundWorkIsCancelledAtTheEnd() {
        var ticks = 0
        lateinit var job: kotlinx.coroutines.Job
        simulate(config = quiet) {
            job = after(0.milliseconds) { while (true) { delay(1.seconds); ticks++ } }
            sleep(5.5.seconds)
        }
        assertTrue(job.isCancelled, "background jobs must be cancelled when the simulation ends")
        val before = ticks
        assertTrue(before in 5..7, "ticks=$before")
    }
}
