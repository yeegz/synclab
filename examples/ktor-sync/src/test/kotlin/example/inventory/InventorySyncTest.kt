package example.inventory

import dev.synclab.FaultType
import dev.synclab.NetworkConditions
import dev.synclab.SimConfig
import dev.synclab.Simulation
import dev.synclab.SimulationFailure
import dev.synclab.ViolationKind
import dev.synclab.duration
import dev.synclab.explore
import dev.synclab.junit5.SyncLabTest
import dev.synclab.ktor.SimHttpResponse
import dev.synclab.ktor.httpEngine
import dev.synclab.ktor.serveHttp
import dev.synclab.nextId
import dev.synclab.pick
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val INITIAL = 50

private suspend fun Simulation.inventoryScenario(honourKeys: Boolean = true, jitter: Boolean = false) {
    val json = InventoryService.json
    val server = node("inventory", crashable = false) {
        InventoryService(mapOf("lamp" to INITIAL, "desk" to INITIAL), honourKeys).also { svc ->
            serveHttp {
                post("/reservations") { req ->
                    val r = svc.reserve(json.decodeFromString(req.bodyText), req.header("Idempotency-Key"))
                    if (r == null) SimHttpResponse.status(409) else SimHttpResponse.json(json.encodeToString(r), 201)
                }
                get("/stock") { req -> SimHttpResponse.json(json.encodeToString(svc.stock(req.queryParam("sku")!!))) }
            }
        }
    }
    val shops = (1..3).map { i ->
        // The app's own client factory, with the simulated engine injected.
        node("shop-$i") { InventoryClient(createHttpClient(httpEngine("inventory"), jitter)) }
    }

    // What the shops believe they reserved (only calls that returned successfully).
    val confirmed = HashMap<String, Int>()

    always("stock is never negative") { server.app.available.values.all { it >= 0 } }
    always("stock + reserved = initial") {
        listOf("lamp", "desk").all { sku ->
            (server.app.available[sku] ?: 0) + server.app.reservations.filter { it.sku == sku }.sumOf { it.quantity } == INITIAL
        }
    }
    eventuallyEqual("server reserved exactly what shops were told they got",
        { confirmed.toSortedMap() },
        { server.app.reservations.groupBy { it.sku }.mapValues { (_, rs) -> rs.sumOf { it.quantity } }.toSortedMap() })

    // An intent whose request failed after all of Ktor's retries has an unknown outcome: it may
    // have been applied. Like an outbox, keep the key and try again later with the *same* key.
    val unresolved = ArrayList<Pair<dev.synclab.SimNode<InventoryClient>, Triple<String, Int, String>>>()

    suspend fun attempt(shop: dev.synclab.SimNode<InventoryClient>, sku: String, qty: Int, key: String): Boolean =
        shop.perform("reserve $qty $sku") {
            try {
                val r = reserve(sku, qty, key)
                confirmed[r.sku] = (confirmed[r.sku] ?: 0) + r.quantity
                true
            } catch (_: OutOfStock) {
                true
            } catch (_: Exception) {
                false
            }
        }

    repeat(random.nextInt(8, 20)) {
        sleep(random.duration(100.milliseconds..2.seconds))
        val shop = random.pick(shops)
        val sku = random.pick("lamp", "desk")
        val qty = random.nextInt(1, 6)
        val key = random.nextId("intent-")
        if (!attempt(shop, sku, qty, key)) unresolved += shop to Triple(sku, qty, key)
    }
    for ((shop, intent) in unresolved) {
        while (!attempt(shop, intent.first, intent.second, intent.third)) sleep(5.seconds)
    }
}

class InventorySyncTest {
    @SyncLabTest
    fun `Ktor client with retries and idempotency keys keeps stock consistent`() {
        val result = explore(runs = 100, config = SimConfig { network = NetworkConditions.Flaky }) { inventoryScenario() }
        assertTrue((result.faultCoverage[FaultType.RESPONSE_LOSS] ?: 0) > 10, "${result.faultCoverage}")
    }

    @SyncLabTest
    fun `ignoring the Idempotency-Key double-reserves when a response is lost`() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 200, config = SimConfig { network = NetworkConditions.Flaky }) { inventoryScenario(honourKeys = false) }
        }
        assertEquals("server reserved exactly what shops were told they got", failure.report.violation.name, failure.message)
        assertTrue(FaultType.RESPONSE_LOSS in failure.report.faultsInvolved || FaultType.DUPLICATE in failure.report.faultsInvolved, failure.message)
    }

    @SyncLabTest
    fun `Ktor's default retry jitter is reported as nondeterminism`() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 50, config = SimConfig { network = NetworkConditions.Hostile }) { inventoryScenario(jitter = true) }
        }
        assertEquals(ViolationKind.NONDETERMINISM, failure.report.violation.kind, failure.message)
    }
}

