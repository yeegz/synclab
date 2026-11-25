package dev.synclab

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val perfect = SimConfig { network = NetworkConditions.Perfect; verifyDeterminism = false }

private class Phone(val storage: DurableStorage) {
    var memoryOnly = 0
    val outbox: MutableList<String> get() = storage.getOrPut("outbox") { mutableListOf() }
}

class NodeTest {
    @Test
    fun crashLosesMemoryKeepsDurableStorageAndRebuildsApp() {
        simulate(config = perfect) {
            val phone = node("phone") { Phone(storage) }
            phone.app.memoryOnly = 5
            phone.app.outbox += "op-1"
            phone.crash()
            assertFalse(phone.isUp)
            assertNull(phone.appOrNull)
            assertFailsWith<NodeDownException> { phone.app }
            phone.restart()
            assertEquals(0, phone.app.memoryOnly)
            assertEquals(listOf("op-1"), phone.app.outbox)
            assertEquals(1, phone.restarts)
        }
    }

    @Test
    fun crashCancelsBackgroundLoops() {
        var ticks = 0
        simulate(config = perfect) {
            val n = node("n") { every(1.seconds) { ticks++ } }
            sleep(3.5.seconds)
            n.crash()
            sleep(10.seconds)
            // (After the scenario, settling restarts crashed nodes, so the loop resumes then.)
            assertEquals(3, ticks)
        }
    }

    @Test
    fun performThrowsNodeDownWhenTheNodeCrashesMidOperation() {
        simulate(config = perfect) {
            val n = node("n") { this }
            after(500.milliseconds) { n.crash() }
            assertFailsWith<NodeDownException> { n.perform("slow work") { delay(2.seconds) } }
            // The scenario itself keeps running.
            assertEquals(500.milliseconds, now)
        }
    }

    @Test
    fun messagesToACrashedNodeAreDropped() {
        var handled = 0
        simulate(config = perfect) {
            val server = node("server") { serve<String, String> { _, r -> handled++; r } }
            val client = node("client") { rpc<String, String>("server", timeout = 1.seconds) }
            server.crash()
            assertFailsWith<SimulatedNetworkException.Unreachable> { client.perform("call") { invoke("x") } }
            server.restart()
            assertEquals("x", client.perform("call") { invoke("x") })
        }
        assertEquals(1, handled)
    }

    @Test
    fun crashedNodesRestartBeforeSettling() {
        simulate(config = perfect) {
            val n = node("n") { this }
            n.crash()
            eventually("node is back") { n.isUp }
        }
    }

    @Test
    fun crashCanBeDisabledForShrinking() {
        simulate(config = perfect.copy { disable(FaultType.CRASH) }) {
            val n = node("n") { this }
            n.crash()
            assertTrue(n.isUp, "crash must be suppressed when CRASH is disabled")
        }
    }

    @Test
    fun clockSkewGivesEachNodeADifferentButReproducibleWallClock() {
        fun offsets(seed: Long): List<Long> {
            var out = listOf<Long>()
            simulate(seed = seed, config = perfect.copy { clockSkew = 5.seconds }) {
                val ns = (1..4).map { i -> node("n$i") { } }
                out = ns.map { it.clock.offsetMillis }
                ns.forEach { assertTrue(it.clock.offsetMillis in -5000..5000) }
                assertEquals(SimClock.EPOCH_START + ns[0].clock.offsetMillis, ns[0].clock.nowMillis())
            }
            return out
        }
        val a = offsets(1)
        assertEquals(a, offsets(1))
        assertTrue(a.toSet().size > 1, "nodes should disagree about the time: $a")
    }

    @Test
    fun clockJumpIsTraced() {
        val r = simulate(config = perfect) {
            val n = node("n") { }
            n.clock.jump((-30).seconds)
            assertEquals(SimClock.EPOCH_START - 30_000, n.clock.nowMillis())
        }
        assertTrue(FaultType.CLOCK_SKEW in r.faultsSeen)
    }

    @Test
    fun chaosDisconnectsAndCrashesNodesWhileTheScenarioRuns() {
        val r = simulate(config = perfect.copy {
            chaos {
                disconnects(every = 1.seconds..2.seconds, lasting = 100.milliseconds..500.milliseconds)
                crashes(every = 2.seconds..4.seconds, downFor = 100.milliseconds..500.milliseconds)
            }
        }) {
            val nodes = (1..3).map { i -> node("n$i") { } }
            sleep(30.seconds)
            eventually("all nodes up after settling") { nodes.all { it.isUp } }
        }
        assertTrue(FaultType.PARTITION in r.faultsSeen && FaultType.CRASH in r.faultsSeen, "${r.faultsSeen}")
        assertTrue(r.trace.any { it.kind == EventKind.RESTART })
    }

    @Test
    fun nonCrashableNodesAreSparedByChaos() {
        val r = simulate(config = perfect.copy { chaos { crashes(every = 100.milliseconds..200.milliseconds) } }) {
            node("server", crashable = false) { }
            sleep(10.seconds)
        }
        assertTrue(r.trace.none { it.kind == EventKind.CRASH })
    }

    @Test
    fun invariantsSkipNodesThatAreDown() {
        simulate(config = perfect) {
            val n = node("n") { Phone(storage) }
            always("counter non-negative") { n.app.memoryOnly >= 0 }
            n.crash()
            sleep(1.seconds)
            n.restart()
        }
    }

    @Test
    fun duplicateNodeNamesAreRejected() {
        assertFailsWith<SimulationFailure> {
            simulate(config = perfect) {
                node("a") { }
                node("a") { }
            }
        }
    }
}
