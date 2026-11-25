package dev.synclab

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun cfg(block: NetworkConditions.Builder.() -> Unit) = SimConfig {
    network = NetworkConditions(block)
    verifyDeterminism = false
}

/** A tiny server that counts how many times it executed each request. */
private class Counter {
    val executions = HashMap<String, Int>()
}

class NetworkTest {
    @Test
    fun rpcRoundTripTakesTwoLatencies() {
        simulate(config = cfg { latency = 50.milliseconds..50.milliseconds }) {
            node("server") { serve<String, String> { _, req -> "echo $req" } }
            val client = node("client") { rpc<String, String>("server") }
            assertEquals("echo hi", client.perform("call") { invoke("hi") })
            assertEquals(100.milliseconds, now)
        }
    }

    @Test
    fun duplicateDeliveryExecutesHandlerTwice() {
        lateinit var counter: Counter
        val result = simulate(config = cfg { duplicateRate = 1.0 }) {
            val server = node("server") { Counter().also { c -> serve<String, Unit> { _, req -> c.executions[req] = (c.executions[req] ?: 0) + 1 } } }
            val client = node("client") { rpc<String, Unit>("server") }
            client.perform("push") { invoke("op-1") }
            sleep(1.seconds)
            counter = server.app
        }
        assertEquals(2, counter.executions["op-1"])
        assertTrue(FaultType.DUPLICATE in result.faultsSeen)
    }

    @Test
    fun responseLossMeansProcessedButCallerTimesOut() {
        var executed = 0
        simulate(config = cfg { responseLossRate = 1.0; requestTimeout = 2.seconds }) {
            node("server") { serve<String, String> { _, _ -> executed++; "ok" } }
            val client = node("client") { rpc<String, String>("server") }
            assertFailsWith<SimulatedNetworkException.Timeout> { client.perform("call") { invoke("x") } }
        }
        assertEquals(1, executed, "the server processed the request even though the client saw a timeout")
    }

    @Test
    fun dropMeansNotProcessed() {
        var executed = 0
        simulate(config = cfg { dropRate = 1.0; requestTimeout = 1.seconds }) {
            node("server") { serve<String, String> { _, _ -> executed++; "ok" } }
            val client = node("client") { rpc<String, String>("server") }
            assertFailsWith<SimulatedNetworkException.Timeout> { client.perform("call") { invoke("x") } }
        }
        assertEquals(0, executed)
    }

    @Test
    fun requestFailureFailsFastWithoutProcessing() {
        var executed = 0
        simulate(config = cfg { requestFailureRate = 1.0 }) {
            node("server") { serve<String, String> { _, _ -> executed++; "ok" } }
            val client = node("client") { rpc<String, String>("server") }
            assertFailsWith<SimulatedNetworkException.RequestFailed> { client.perform("call") { invoke("x") } }
            assertTrue(now < 1.seconds)
        }
        assertEquals(0, executed)
    }

    @Test
    fun disconnectedNodeIsUnreachableUntilReconnected() {
        simulate(config = cfg { }) {
            node("server") { serve<String, String> { _, r -> r } }
            val client = node("client") { rpc<String, String>("server") }
            network.disconnect("client")
            assertFailsWith<SimulatedNetworkException.Unreachable> { client.perform("call") { invoke("x") } }
            network.reconnect("client")
            assertEquals("x", client.perform("call") { invoke("x") })
        }
    }

    @Test
    fun partitionCutsMessagesAlreadyInFlight() {
        var received = 0
        simulate(config = cfg { latency = 100.milliseconds..100.milliseconds }) {
            node("b") { onMessage<String> { _, _ -> received++ } }
            val a = node("a") { this }
            a.app.send("b", "hello")
            sleep(50.milliseconds)
            network.partition(listOf("a"), listOf("b"))
            sleep(200.milliseconds)
        }
        assertEquals(0, received, "a message arriving across a cut link must be dropped")
    }

    @Test
    fun fifoLinksWhenReorderOff() {
        val got = mutableListOf<Int>()
        simulate(config = cfg { latency = 1.milliseconds..300.milliseconds; reorder = false }) {
            node("b") { onMessage<Int> { _, m -> got += m } }
            val a = node("a") { this }
            repeat(50) { a.app.send("b", it) }
            sleep(2.seconds)
        }
        assertEquals((0 until 50).toList(), got)
    }

    @Test
    fun reorderLetsMessagesOvertake() {
        val got = mutableListOf<Int>()
        simulate(config = cfg { latency = 1.milliseconds..300.milliseconds; reorder = true }) {
            node("b") { onMessage<Int> { _, m -> got += m } }
            val a = node("a") { this }
            repeat(50) { a.app.send("b", it) }
            sleep(2.seconds)
        }
        assertEquals(50, got.size)
        assertTrue(got != got.sorted(), "with reorder on and wide latency, 50 messages should not arrive in order")
    }

    @Test
    fun customInterceptorIsAFaultTypeTheShrinkerKnows() {
        val corrupt = FaultType("CORRUPT")
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = SimConfig { network = NetworkConditions.Flaky }) {
                network.intercept(corrupt) { msg, _ -> if (msg.payload == 10) Verdict.Replace(-10) else Verdict.Pass }
                var total = 0
                node("bank") { serve<Int, Unit> { _, amount -> total += amount } }
                val atm = node("atm") { rpc<Int, Unit>("bank") }
                always("balance never negative") { total >= 0 }
                atm.perform("deposit") { invoke(10) }
            }
        }
        assertEquals(setOf(corrupt), failure.report.faultsInvolved, failure.report.render())
        assertTrue(failure.report.minimised)
    }

    @Test
    fun missingServiceIsAClearConfigurationError() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = cfg { }) {
                node("server") { }
                val c = node("client") { rpc<String, String>("server", service = "sync") }
                runCatching { c.perform("call") { invoke("x") } }
            }
        }
        assertTrue("nothing serves it" in failure.report.violation.message, failure.message)
    }

    @Test
    fun handlerExceptionFailsTheRunByDefault() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = cfg { }) {
                node("server") { serve<String, String> { _, _ -> error("boom") } }
                val c = node("client") { rpc<String, String>("server") }
                runCatching { c.perform("call") { invoke("x") } }
                delay(10)
            }
        }
        assertEquals(ViolationKind.UNCAUGHT, failure.report.violation.kind)
        assertEquals("server", failure.report.violation.node)
    }

    @Test
    fun handlerExceptionCanBeARemoteErrorInstead() {
        simulate(config = cfg { }.copy { failOnHandlerException = false }) {
            node("server") { serve<String, String> { _, _ -> throw IllegalArgumentException("bad input") } }
            val c = node("client") { rpc<String, String>("server") }
            val e = assertFailsWith<SimulatedNetworkException.Remote> { c.perform("call") { invoke("x") } }
            assertTrue("bad input" in e.message!!)
        }
    }
}
