package dev.synclab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A deliberately naive replicated counter: the client retries on any error and the server applies
 * every increment it receives. Under DUPLICATE or RESPONSE_LOSS it double-counts.
 */
private suspend fun Simulation.naiveCounter(idempotent: Boolean, ops: Int = 20) {
    val applied = LinkedHashSet<String>()
    var serverTotal = 0
    node("server") {
        serve<Pair<String, Int>, Unit> { _, (id, amount) ->
            if (!idempotent || applied.add(id)) serverTotal += amount
        }
    }
    val phone = node("phone") { rpc<Pair<String, Int>, Unit>("server") }
    var intended = 0
    eventuallyEqual("server total matches what the user entered", { intended }, { serverTotal })
    repeat(ops) { i ->
        intended += 1
        phone.perform("increment $i") {
            while (true) {
                try { invoke("op-$i" to 1); break } catch (e: SimulatedNetworkException) { kotlinx.coroutines.delay(100) }
            }
        }
    }
}

class RunTest {
    @Test
    fun naiveRetryIsCaughtAndShrunkToTheCausalFaults() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 50) { naiveCounter(idempotent = false) }
        }
        val r = failure.report
        assertEquals(ViolationKind.NOT_CONVERGED, r.violation.kind)
        assertTrue(r.minimised)
        // Only duplication-type faults can make a retried, non-idempotent increment double count.
        assertTrue(r.faultsInvolved.isNotEmpty() && r.faultsInvolved.all { it == FaultType.DUPLICATE || it == FaultType.RESPONSE_LOSS },
            "unexpected minimal fault set ${r.faultsInvolved}\n${r.render()}")
        assertTrue("SYNCLAB_REPLAY=SL1:" in failure.message!!)
        assertTrue("expected: 20" in r.violation.message, r.violation.message)
        assertTrue("Injected faults" in failure.message!!)
    }

    @Test
    fun idempotentVersionPassesManySeeds() {
        val result = explore(runs = 60) { naiveCounter(idempotent = true) }
        assertEquals(60, result.runs)
        assertTrue(FaultType.DUPLICATE in result.faultCoverage.keys, "coverage should show duplicates were exercised: ${result.faultCoverage}")
        assertTrue(FaultType.RESPONSE_LOSS in result.faultCoverage.keys)
    }

    @Test
    fun replayTokenReproducesTheSameFailure() {
        val failure = assertFailsWith<SimulationFailure> { explore(runs = 50) { naiveCounter(idempotent = false) } }
        val again = assertFailsWith<SimulationFailure> {
            simulate(replay = failure.report.replayToken) { naiveCounter(idempotent = false) }
        }
        assertEquals(failure.report.stats.fingerprint, again.report.stats.fingerprint)
        assertEquals(failure.report.violation.name, again.report.violation.name)
    }

    @Test
    fun replayAppliesTheDisabledFaultsFromTheToken() {
        val config = SimConfig {
            network = NetworkConditions.Perfect
            disable(FaultType.PARTITION)
        }
        val scenario: suspend Simulation.() -> Unit = {
            node("phone") { }
            node("server") { }
            network.partition(listOf("phone"), listOf("server"))
        }
        val recorded = simulate(config = config, scenario = scenario)
        val replayed = simulate(replay = recorded.replayToken, scenario = scenario)
        assertTrue(replayed.trace.any { it.kind == EventKind.SUPPRESSED && it.fault == FaultType.PARTITION })
        assertEquals(recorded.stats.fingerprint, replayed.stats.fingerprint)
    }

    @Test
    fun traceFingerprintChangesWhenOnlyAnEventDetailChanges() {
        val first = simulate(seed = 42) { note("sent three updates") }
        val second = simulate(seed = 42) { note("sent four updates") }
        assertNotEquals(first.stats.fingerprint, second.stats.fingerprint)
    }

    @Test
    fun sameSeedSameTrace() {
        val a = simulate(seed = 42) { naiveCounter(idempotent = true) }
        val b = simulate(seed = 42) { naiveCounter(idempotent = true) }
        assertEquals(a.stats.fingerprint, b.stats.fingerprint)
        assertEquals(a.trace.map { it.canonical() }, b.trace.map { it.canonical() })
        val c = simulate(seed = 43) { naiveCounter(idempotent = true) }
        assertNotEquals(a.stats.fingerprint, c.stats.fingerprint)
    }

    @Test
    fun runsAreIsolated() {
        // State created inside the scenario must not leak between runs: explore checks this by
        // re-running the first seed and comparing traces.
        explore(runs = 5) { naiveCounter(idempotent = true) }
    }

    @Test
    fun nondeterministicCodeIsDetected() {
        // State that lives outside the scenario changes between the two determinism runs,
        // just like Random.Default, System.nanoTime() or a shared singleton would.
        var calls = 0
        val failure = assertFailsWith<SimulationFailure> {
            simulate {
                val n = ++calls
                node("a") { }
                note("doing $n things")
                repeat(n) { sleep(10.milliseconds) }
            }
        }
        assertEquals(ViolationKind.NONDETERMINISM, failure.report.violation.kind)
        assertTrue("Random.Default" in failure.report.violation.message)
    }

    @Test
    fun alwaysInvariantFailsAtTheExactStep() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = SimConfig { network = NetworkConditions.Perfect }) {
                var x = 0
                always("x stays below 3") { x < 3 }
                repeat(5) { x++; sleep(1.seconds) }
            }
        }
        assertEquals(ViolationKind.INVARIANT, failure.report.violation.kind)
        assertEquals(2.seconds.inWholeMilliseconds, failure.report.violation.atMillis)
    }

    @Test
    fun convergesExplainsTheDifference() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = SimConfig { network = NetworkConditions.Perfect; settleTimeout = 2.seconds }) {
                val a = mapOf("milk" to 1, "eggs" to 12)
                val b = mapOf("milk" to 2, "bread" to 1)
                converges("lists match") { listOf("phone" to a, "laptop" to b) }
            }
        }
        val msg = failure.report.violation.message
        assertTrue("only in first: eggs" in msg && "only in second: bread" in msg && "milk: 1  vs  2" in msg, msg)
        assertTrue("went idle" in msg, msg)
    }

    @Test
    fun assertionInScenarioIsReportedWithTrace() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = SimConfig { network = NetworkConditions.Perfect }) {
                note("about to fail")
                assertEquals(1, 2, "numbers differ")
            }
        }
        assertEquals(ViolationKind.ASSERTION, failure.report.violation.kind)
        assertTrue(failure.report.trace.any { it.detail == "about to fail" })
        assertTrue(failure.report.faultsInvolved.isEmpty())
        assertTrue("fault-free network" in failure.message!!)
    }

    @Test
    fun replayTokenRoundTrip() {
        val t = ReplayToken(-12345, setOf(FaultType.DROP, FaultType.CRASH), 0x1234_5678_9abc_def0)
        val parsed = ReplayToken.parse(t.toString())
        assertEquals(t.toString(), parsed.toString())
        assertEquals(-12345, parsed.seed)
        assertEquals(setOf(FaultType.DROP, FaultType.CRASH), parsed.disabledFaults)
        assertTrue(parsed.matches(0x1234_5678_0000_0000))
        assertFalse(parsed.matches(0x1234_5679_0000_0000))
        assertFailsWith<IllegalArgumentException> { ReplayToken.parse("hello") }
    }

    @Test
    fun configValidation() {
        assertFailsWith<IllegalArgumentException> { NetworkConditions { dropRate = 1.5 } }
        assertFailsWith<IllegalArgumentException> { FaultType("has space") }
        val only = SimConfig { only(FaultType.DROP) }
        assertTrue(only.isEnabled(FaultType.DROP))
        assertFalse(only.isEnabled(FaultType.DUPLICATE))
    }
}

