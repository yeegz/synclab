package dev.synclab.docs

import dev.synclab.FaultType
import dev.synclab.SimulatedNetworkException
import dev.synclab.SimulationFailure
import dev.synclab.explore
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Keep in sync with docs/QUICKSTART.md. This file proves the quick start compiles and behaves as described.

class CounterServer {
    var total = 0
    fun add(amount: Int) { total += amount }
}

class CounterClient(private val send: suspend (Int) -> Unit) {
    suspend fun add(amount: Int) {
        while (true) {
            try { send(amount); return } catch (e: SimulatedNetworkException) { delay(200) }
        }
    }
}

class IdempotentServer {
    var total = 0
    private val applied = HashSet<String>()
    fun add(id: String, amount: Int) { if (applied.add(id)) total += amount }
}

class IdempotentClient(private val send: suspend (id: String, amount: Int) -> Unit) {
    private var next = 0
    suspend fun add(amount: Int) {
        val id = "tap-${next++}"                // chosen once, reused by every retry
        while (true) {
            try { send(id, amount); return } catch (e: SimulatedNetworkException) { delay(200) }
        }
    }
}

class QuickstartTest {
    @Test
    fun `the naive counter double counts`() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 500) {
                val server = node("server") {
                    CounterServer().also { s -> serve<Int, Unit> { _, amount -> s.add(amount) } }
                }
                val phone = node("phone") {
                    val call = rpc<Int, Unit>("server")
                    CounterClient { amount -> call(amount) }
                }

                var taps = 0
                eventuallyEqual("server total equals taps", { taps }, { server.app.total })

                repeat(random.nextInt(1, 20)) {
                    taps++
                    phone.perform("tap") { add(1) }
                }
            }
        }
        assertTrue(failure.report.faultsInvolved.all { it == FaultType.RESPONSE_LOSS || it == FaultType.DUPLICATE }, failure.message)
    }

    @Test
    fun `the idempotent counter passes 500 worlds`() {
        explore(runs = 500) {
            val server = node("server") {
                IdempotentServer().also { s -> serve<Pair<String, Int>, Unit> { _, (id, amount) -> s.add(id, amount) } }
            }
            val phone = node("phone") {
                val call = rpc<Pair<String, Int>, Unit>("server")
                IdempotentClient { id, amount -> call(id to amount) }
            }
            var taps = 0
            eventuallyEqual("server total equals taps", { taps }, { server.app.total })
            repeat(random.nextInt(1, 20)) {
                taps++
                phone.perform("tap") { add(1) }
            }
        }
    }
}
