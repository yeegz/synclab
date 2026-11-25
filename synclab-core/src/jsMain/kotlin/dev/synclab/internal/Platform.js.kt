package dev.synclab.internal

import dev.synclab.SimulationListener

internal actual fun readSetting(property: String, env: String): String? {
    val value: dynamic = js("(typeof process !== 'undefined' && process.env) ? process.env[env] : undefined")
    return (value as? String)?.takeIf { it.isNotBlank() }
}

internal actual fun captureThread(): Any? = null

internal actual fun isSameThread(handle: Any): Boolean = true

internal actual fun currentThreadName(): String = "main"

internal actual fun discoverListeners(): List<SimulationListener> = emptyList()

internal actual fun guessTestName(): String? = null

internal actual fun brieflyWaitForForeignWork() {}
