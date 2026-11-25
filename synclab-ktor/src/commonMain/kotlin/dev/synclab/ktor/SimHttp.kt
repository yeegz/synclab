package dev.synclab.ktor

import dev.synclab.NodeScope
import dev.synclab.SimulatedNetworkException
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration

/** An HTTP request as it travels over the simulated network. Bodies are copied, so it is immutable. */
public class SimHttpRequest(
    public val method: String,
    /** Path without the query string, e.g. `/expenses`. */
    public val path: String,
    public val query: Map<String, List<String>>,
    public val headers: Map<String, List<String>>,
    private val bodyBytes: ByteArray,
) {
    public val body: ByteArray get() = bodyBytes.copyOf()
    public val bodyText: String get() = bodyBytes.decodeToString()

    public fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    public fun queryParam(name: String): String? = query[name]?.firstOrNull()

    override fun toString(): String = "$method $path${if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }}" +
        (if (bodyBytes.isEmpty()) "" else " ${bodyPreview(bodyBytes)}")
}

/** An HTTP response travelling back over the simulated network. */
public class SimHttpResponse(
    public val status: Int,
    public val headers: Map<String, List<String>> = emptyMap(),
    private val bodyBytes: ByteArray = ByteArray(0),
) {
    public val body: ByteArray get() = bodyBytes.copyOf()
    public val bodyText: String get() = bodyBytes.decodeToString()

    override fun toString(): String = "$status${if (bodyBytes.isEmpty()) "" else " " + bodyPreview(bodyBytes)}"

    public companion object {
        public fun text(text: String, status: Int = 200, contentType: String = "text/plain"): SimHttpResponse =
            SimHttpResponse(status, mapOf(HttpHeaders.ContentType to listOf(contentType)), text.encodeToByteArray())

        public fun json(json: String, status: Int = 200): SimHttpResponse = text(json, status, "application/json")

        public fun status(status: Int): SimHttpResponse = SimHttpResponse(status)
    }
}

private fun bodyPreview(bytes: ByteArray): String {
    val s = bytes.decodeToString()
    return if (s.length <= 100) s else s.take(99) + "…"
}

/**
 * Thrown by the simulated engine when the network fails. It is a [kotlinx.io.IOException], so
 * apps and Ktor plugins (e.g. `HttpRequestRetry.retryOnException`) treat it like a real I/O error.
 */
public class SimulatedIOException(message: String, cause: Throwable) : kotlinx.io.IOException(message, cause)

/** Routing for a simulated HTTP server: exact method + path matches. */
public class SimHttpRoutes internal constructor() {
    internal val routes = ArrayList<Triple<String, String, suspend (SimHttpRequest) -> SimHttpResponse>>()
    internal var fallback: suspend (SimHttpRequest) -> SimHttpResponse = { SimHttpResponse.status(HttpStatusCode.NotFound.value) }

    public fun get(path: String, handler: suspend (SimHttpRequest) -> SimHttpResponse) { routes += Triple("GET", path, handler) }
    public fun post(path: String, handler: suspend (SimHttpRequest) -> SimHttpResponse) { routes += Triple("POST", path, handler) }
    public fun put(path: String, handler: suspend (SimHttpRequest) -> SimHttpResponse) { routes += Triple("PUT", path, handler) }
    public fun delete(path: String, handler: suspend (SimHttpRequest) -> SimHttpResponse) { routes += Triple("DELETE", path, handler) }
    public fun otherwise(handler: suspend (SimHttpRequest) -> SimHttpResponse) { fallback = handler }

    internal suspend fun handle(req: SimHttpRequest): SimHttpResponse =
        routes.firstOrNull { it.first == req.method && it.second == req.path }?.third?.invoke(req) ?: fallback(req)
}

/** Default service name used for HTTP traffic between nodes. */
public const val HTTP_SERVICE: String = "http"

/**
 * Makes this node an HTTP server reachable through the simulated network.
 *
 * ```
 * node("api") {
 *     val store = Store()
 *     serveHttp { post("/items") { req -> store.add(req.bodyText); SimHttpResponse.status(201) } }
 *     store
 * }
 * ```
 */
public fun NodeScope.serveHttp(service: String = HTTP_SERVICE, routes: SimHttpRoutes.() -> Unit) {
    val r = SimHttpRoutes().apply(routes)
    serve<SimHttpRequest, SimHttpResponse>(service) { _, req -> r.handle(req) }
}

/**
 * A Ktor [HttpClientEngine] whose requests travel from this node to node [to] over the simulated
 * network, with all of its faults. Pass it wherever your app builds its `HttpClient(engine) { … }`,
 * so your real plugins (JSON, retries, timeouts, auth) run under simulation unchanged.
 */
public fun NodeScope.httpEngine(to: String, service: String = HTTP_SERVICE, timeout: Duration? = null): HttpClientEngine {
    val call = rpc<SimHttpRequest, SimHttpResponse>(to, service, timeout)
    val config = MockEngineConfig()
    // Keep Ktor on the simulation's dispatcher so virtual time and ordering apply to it too.
    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)?.let { config.dispatcher = it }
    config.reuseHandlers = true
    config.addHandler { request ->
        val simRequest = SimHttpRequest(
            method = request.method.value,
            path = request.url.encodedPath.ifEmpty { "/" },
            query = request.url.parameters.entries().associate { it.key to it.value },
            headers = (request.headers.entries() + request.body.headers.entries()).associate { it.key to it.value } +
                (request.body.contentType?.let { mapOf(HttpHeaders.ContentType to listOf(it.toString())) } ?: emptyMap()),
            bodyBytes = request.body.toByteArray(),
        )
        val response = try {
            call(simRequest)
        } catch (e: SimulatedNetworkException) {
            throw SimulatedIOException(e.message ?: "simulated network failure", e)
        }
        respond(
            content = ByteReadChannel(response.body),
            status = HttpStatusCode.fromValue(response.status),
            headers = headersOf(*response.headers.map { (k, v) -> k to v }.toTypedArray()),
        )
    }
    return MockEngine(config)
}

/** Convenience: an [HttpClient] using [httpEngine]. */
public fun NodeScope.httpClient(
    to: String,
    service: String = HTTP_SERVICE,
    timeout: Duration? = null,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(httpEngine(to, service, timeout), configure)
