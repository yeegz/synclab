package dev.synclab.internal

import dev.synclab.SimulationListener
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker

@OptIn(ExperimentalForeignApi::class)
internal actual fun readSetting(property: String, env: String): String? =
    getenv(env)?.toKString()?.takeIf { it.isNotBlank() }

@OptIn(ObsoleteWorkersApi::class)
internal actual fun captureThread(): Any? = Worker.current.id

@OptIn(ObsoleteWorkersApi::class)
internal actual fun isSameThread(handle: Any): Boolean = handle == Worker.current.id

@OptIn(ObsoleteWorkersApi::class)
internal actual fun currentThreadName(): String = "worker-${Worker.current.id}"

internal actual fun discoverListeners(): List<SimulationListener> = emptyList()

internal actual fun guessTestName(): String? = null

internal actual fun brieflyWaitForForeignWork() {}
