package dev.synclab.ktor

import dev.synclab.NetworkConditions
import dev.synclab.SimConfig
import dev.synclab.simulate
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val perfect = SimConfig { network = NetworkConditions { latency = 20.milliseconds..20.milliseconds } }

class SimHttpTest {
    @Test
    fun requestAndResponseTravelThroughTheSimulatedNetwork() {
        simulate(config = perfect) {
            node("api") {
                serveHttp {
                    post("/echo") { req ->
                        SimHttpResponse.text("${req.method} ${req.path} k=${req.header("X-Key")} q=${req.queryParam("q")} body=${req.bodyText}")
                    }
                }
            }
            val app = node("app") { httpClient("api") }
            val text = app.perform("echo") {
                post("http://api/echo?q=1") { header("X-Key", "abc"); setBody("hello") }.bodyAsText()
            }
            assertEquals("POST /echo k=abc q=1 body=hello", text)
            assertEquals(40.milliseconds, now, "one request + one response at 20 ms each, in virtual time")
        }
    }

    @Test
    fun unknownRouteIs404() {
        simulate(config = perfect) {
            node("api") { serveHttp { } }
            val app = node("app") { httpClient("api") }
            assertEquals(404, app.perform("get") { get("http://api/missing").status.value })
        }
    }

    @Test
    fun networkFailuresSurfaceAsIoExceptions() {
        simulate(config = perfect) {
            node("api") { serveHttp { get("/") { SimHttpResponse.status(200) } } }
            val app = node("app") { httpClient("api") }
            network.disconnect("app")
            val e = assertFailsWith<IOException> { app.perform("get") { get("http://api/") } }
            assertTrue(e is SimulatedIOException)
        }
    }

    @Test
    fun ktorTimeoutPluginRunsOnVirtualTime() {
        simulate(config = perfect.copy { network { responseLossRate = 1.0 } }) {
            node("api") { serveHttp { get("/") { SimHttpResponse.status(200) } } }
            val app = node("app") { httpClient("api") { install(HttpTimeout) { requestTimeoutMillis = 1_500 } } }
            assertFailsWith<Exception> { app.perform("get") { get("http://api/") } }
            assertEquals(1.5.seconds, now)
        }
    }
}
