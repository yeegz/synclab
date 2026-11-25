package dev.synclab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmSpecificTest {
    @Test
    fun workOnDispatchersIoIsDetected() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate {
                withContext(Dispatchers.IO) { Thread.sleep(5) }
            }
        }
        assertEquals(ViolationKind.ESCAPED_DISPATCHER, failure.report.violation.kind)
        assertTrue("Dispatchers.IO" in failure.message!!)
    }

    @Test
    fun listenersAreNotifiedAndCannotBreakTests() {
        val seen = ConcurrentLinkedQueue<RunSummary>()
        val good = object : SimulationListener { override fun onFinished(summary: RunSummary) { seen += summary } }
        val bad = object : SimulationListener { override fun onFinished(summary: RunSummary) = error("reporter bug") }
        SyncLab.addListener(bad)
        SyncLab.addListener(good)
        try {
            explore(runs = 3, config = SimConfig { network = NetworkConditions.Perfect }) { node("a") { } }
        } finally {
            SyncLab.removeListener(bad)
            SyncLab.removeListener(good)
        }
        val s = seen.single()
        assertTrue(s.passed)
        assertEquals(3, s.runs)
        assertEquals("dev.synclab.JvmSpecificTest.listenersAreNotifiedAndCannotBreakTests", s.testName)
    }

    @Test
    fun simulationsAreIsolatedAcrossThreads() {
        // Many simulations in parallel on different threads must not interfere.
        val pool = Executors.newFixedThreadPool(8)
        val prints = (1..32).map { seed ->
            pool.submit<Long> {
                simulate(seed = seed.toLong()) {
                    val s = node("s") { serve<Int, Int> { _, x -> x * 2 } }
                    val c = node("c") { rpc<Int, Int>("s") }
                    repeat(20) { i -> runCatching { c.perform("call") { invoke(i) } } }
                    s.app
                }.stats.fingerprint
            }
        }.map { it.get() }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
        val sequential = (1..32).map { seed ->
            simulate(seed = seed.toLong()) {
                val s = node("s") { serve<Int, Int> { _, x -> x * 2 } }
                val c = node("c") { rpc<Int, Int>("s") }
                repeat(20) { i -> runCatching { c.perform("call") { invoke(i) } } }
                s.app
            }.stats.fingerprint
        }
        assertEquals(sequential, prints)
    }
}
