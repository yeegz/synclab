package dev.synclab.internal

import dev.synclab.SimulationListener

/** Reads a setting from a JVM system property (`synclab.x`) or environment variable (`SYNCLAB_X`). */
internal expect fun readSetting(property: String, env: String): String?

/** Opaque handle for the current thread, or null on platforms without threads to check. */
internal expect fun captureThread(): Any?

internal expect fun isSameThread(handle: Any): Boolean

internal expect fun currentThreadName(): String

/** Listeners discovered automatically (JVM: `ServiceLoader`). */
internal expect fun discoverListeners(): List<SimulationListener>

/** Best-effort name of the test that started the simulation, e.g. `LedgerTest.balances sum to zero`. */
internal expect fun guessTestName(): String?

/** Called when the queue is empty but work may arrive from a foreign thread (JVM only). */
internal expect fun brieflyWaitForForeignWork()
