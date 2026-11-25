package example.inventory

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// A client-server stock reservation workflow over HTTP, the way a real app would write it:
// Ktor client, JSON, a retry plugin and a timeout plugin. The engine is injected, which is the
// only seam SyncLab needs.

@Serializable
public data class ReserveRequest(val sku: String, val quantity: Int)

@Serializable
public data class Reservation(val id: String, val sku: String, val quantity: Int)

@Serializable
public data class StockLevel(val sku: String, val available: Int, val reservations: List<Reservation>)

public class OutOfStock(message: String) : Exception(message)

/** How the app builds its HTTP client. Production passes a real engine (OkHttp, Darwin, CIO). */
public fun createHttpClient(engine: HttpClientEngine, jitter: Boolean = false): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) { json() }
    install(HttpTimeout) { requestTimeoutMillis = 3_000 }
    install(HttpRequestRetry) {
        maxRetries = 4
        retryOnExceptionOrServerErrors()
        // Ktor's default jitter uses Random.Default, which makes runs irreproducible (SyncLab reports it).
        exponentialDelay(randomizationMs = if (jitter) 1_000 else 0)
    }
    expectSuccess = false
}

public class InventoryClient(private val http: HttpClient, private val baseUrl: String = "http://inventory") {
    /**
     * Reserves stock. [idempotencyKey] identifies the user's intent; it stays the same across the
     * retry plugin's attempts, so the server can recognise a retry of something it already did.
     */
    public suspend fun reserve(sku: String, quantity: Int, idempotencyKey: String): Reservation {
        val response = http.post("$baseUrl/reservations") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey)
            setBody(ReserveRequest(sku, quantity))
        }
        if (response.status.value == 409) throw OutOfStock("Not enough $sku")
        check(response.status.isSuccess()) { "Unexpected status ${response.status}" }
        return response.body()
    }

    public suspend fun stock(sku: String): StockLevel = http.get("$baseUrl/stock?sku=$sku").body()
}

/** Server-side logic, independent of any web framework. */
public class InventoryService(initial: Map<String, Int>, private val honourIdempotencyKeys: Boolean = true) {
    public val available: MutableMap<String, Int> = initial.toMutableMap()
    public val reservations: MutableList<Reservation> = ArrayList()
    private val byKey = HashMap<String, Reservation>()
    private var next = 1

    /** Returns null when out of stock. */
    public fun reserve(req: ReserveRequest, idempotencyKey: String?): Reservation? {
        if (honourIdempotencyKeys && idempotencyKey != null) byKey[idempotencyKey]?.let { return it }
        val left = available[req.sku] ?: 0
        if (req.quantity <= 0 || left < req.quantity) return null
        available[req.sku] = left - req.quantity
        val r = Reservation("r${next++}", req.sku, req.quantity)
        reservations += r
        if (idempotencyKey != null) byKey[idempotencyKey] = r
        return r
    }

    public fun stock(sku: String): StockLevel = StockLevel(sku, available[sku] ?: 0, reservations.filter { it.sku == sku })

    public companion object {
        public val json: Json = Json
    }
}
