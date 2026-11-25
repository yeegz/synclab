package example.ledger

import dev.synclab.FaultType
import dev.synclab.NetworkConditions
import dev.synclab.SimConfig
import dev.synclab.SimNode
import dev.synclab.Simulation
import dev.synclab.SimulationFailure
import dev.synclab.ViolationKind
import dev.synclab.chance
import dev.synclab.duration
import dev.synclab.explore
import dev.synclab.junit5.SyncLabTest
import dev.synclab.nextId
import dev.synclab.pick
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val members = setOf("alice", "bob", "carol")

/** Adapts SyncLab's simulated RPC to the app's own LedgerApi interface. This is the whole integration. */
private class SimLedgerApi(
    private val pushRpc: dev.synclab.RpcClient<PushRequest, PushResponse>,
    private val pullRpc: dev.synclab.RpcClient<Long, PullResponse>,
) : LedgerApi {
    override suspend fun push(request: PushRequest) = pushRpc(request)
    override suspend fun pull(since: Long) = pullRpc(since)
}

/**
 * Builds the world: one server and four devices (Bob has a phone and a laptop), each syncing every
 * two seconds, then drives a random workload. Every seed gives a different workload and fault schedule.
 */
private suspend fun Simulation.ledgerScenario(bugs: LedgerBugs = LedgerBugs(), ops: IntRange = 10..30) {
    val server = node("server", crashable = false) {
        LedgerServer(members, bugs).also { s ->
            serve<PushRequest, PushResponse>("push") { _, req -> s.push(req) }
            serve<Long, PullResponse>("pull") { _, since -> s.pull(since) }
        }
    }
    val devices: List<SimNode<LedgerClient>> = listOf("alice" to "alice", "bob" to "bob-phone", "bob" to "bob-laptop", "carol" to "carol")
        .map { (member, device) ->
            node(device) {
                val disk = storage.getOrPut("disk") { mutableMapOf<String, Any?>() }
                LedgerClient(member, SimLedgerApi(rpc(to = "server", service = "push"), rpc(to = "server", service = "pull")),
                    disk, newId = { random.nextId() }, bugs = bugs).also { client ->
                    every(2.seconds) { runCatching { client.sync() } }
                }
            }
        }

    // ---- Business invariants, checked after every single step -------------------------------
    always("balances sum to zero") { server.app.state.balances(members).values.sum() == 0L }
    always("every expense's shares add up to its amount") {
        server.app.state.expenses.values.all { it.shares.values.sum() == it.amount }
    }
    alwaysEqual("settled totals match confirmed payments",
        expected = {
            server.app.state.payments.values.filter { it.status == PaymentStatus.CONFIRMED }
                .groupBy { it.from to it.to }.mapValues { (_, ps) -> ps.sumOf { it.amount } }
        },
        actual = { server.app.settledTotals.filterValues { it != 0L } })

    // ---- What users expect once everyone is back online ------------------------------------
    val created = LinkedHashSet<String>()
    eventuallyEqual("each expense a user added is on the server exactly once",
        { created.toSortedSet() },
        { server.app.state.expenses.keys.toSortedSet() })
    eventually("every device has synced its outbox") { devices.all { it.appOrNull?.pendingOps?.isEmpty() == true } }
    converges("devices agree with the server") {
        listOf("server" to server.app.state.snapshot()) + devices.map { it.name to it.app.confirmed.snapshot() }
    }

    // ---- Random workload -------------------------------------------------------------------
    repeat(random.nextInt(ops.first, ops.last + 1)) {
        sleep(random.duration(50.milliseconds..1500.milliseconds))
        val device = random.pick(devices)
        if (!device.isUp) return@repeat
        device.perform("user action") {
            val view = confirmed
            val pending = view.payments.values.filter { it.to == member && it.status == PaymentStatus.PENDING }
            val owedTo = (members - member).filter { view.directDebt(member, it) > 0 }
            when {
                pending.isNotEmpty() && random.chance(0.5) -> confirmPayment(random.pick(pending).id)
                owedTo.isNotEmpty() && random.chance(0.4) -> {
                    val to = random.pick(owedTo)
                    recordPayment(to, random.nextLong(1, view.directDebt(member, to) + 1))
                }
                else -> {
                    val participants = members.filter { it == member || random.chance(0.7) }
                    val op = addExpense(random.nextLong(100, 50_000), participants)
                    created += op.expense.id
                }
            }
        }
    }
}

private val hostileWithChaos = SimConfig {
    network = NetworkConditions.Hostile
    clockSkew = 3.seconds
    chaos {
        disconnects(every = 2.seconds..8.seconds, lasting = 500.milliseconds..10.seconds)
        crashes(every = 5.seconds..15.seconds, downFor = 100.milliseconds..3.seconds)
    }
}

class LedgerSyncTest {
    @SyncLabTest
    fun `correct ledger converges under loss, duplicates, partitions and crashes`() {
        val result = explore(runs = 200, config = hostileWithChaos) { ledgerScenario() }
        // Make sure the faults we care about were actually exercised, not just configured.
        for (f in listOf(FaultType.DUPLICATE, FaultType.RESPONSE_LOSS, FaultType.CRASH, FaultType.PARTITION)) {
            assertTrue((result.faultCoverage[f] ?: 0) > 20, "$f was rarely exercised: ${result.faultCoverage}")
        }
    }

    @SyncLabTest
    fun `catches a server that is not idempotent`() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 200) { ledgerScenario(LedgerBugs(nonIdempotentServer = true)) }
        }
        val r = failure.report
        assertEquals("each expense a user added is on the server exactly once", r.violation.name, failure.message)
        assertTrue(r.faultsInvolved.all { it in setOf(FaultType.DUPLICATE, FaultType.RESPONSE_LOSS, FaultType.REQUEST_FAILURE, FaultType.DROP, FaultType.DELAY) },
            failure.message)
    }

    @SyncLabTest
    fun `catches a client that mints a new id when it retries`() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 200) { ledgerScenario(LedgerBugs(newIdOnRetry = true)) }
        }
        assertEquals(ViolationKind.NOT_CONVERGED, failure.report.violation.kind, failure.message)
    }

    @SyncLabTest
    fun `catches confirming a payment twice from two devices`() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 300) { ledgerScenario(LedgerBugs(confirmWithoutRecheck = true), ops = 20..40) }
        }
        assertEquals("settled totals match confirmed payments", failure.report.violation.name, failure.message)
    }
}
