package dev.synclab.internal

import dev.synclab.SimulationListener
import java.util.ServiceLoader

internal actual fun readSetting(property: String, env: String): String? =
    System.getProperty(property)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

internal actual fun captureThread(): Any? = Thread.currentThread()

internal actual fun isSameThread(handle: Any): Boolean = handle === Thread.currentThread()

internal actual fun currentThreadName(): String = Thread.currentThread().name

internal actual fun discoverListeners(): List<SimulationListener> =
    try {
        ServiceLoader.load(SimulationListener::class.java, SimulationListener::class.java.classLoader).toList()
    } catch (e: Throwable) {
        emptyList()
    }

internal actual fun guessTestName(): String? {
    TestNameHolder.current.get()?.let { return it }
    val frames = Thread.currentThread().stackTrace
    val frame = frames.firstOrNull { f ->
        '$' !in f.className &&
            !f.className.startsWith("java.") && !f.className.startsWith("jdk.") &&
            !f.className.startsWith("kotlin.") && !f.className.startsWith("sun.") &&
            !f.className.startsWith("org.junit.") &&
            (f.className.endsWith("Test") || f.className.endsWith("Tests") || f.className.endsWith("Spec"))
    } ?: return null
    return frame.className + "." + frame.methodName
}

internal actual fun brieflyWaitForForeignWork() {
    Thread.sleep(25)
}

/** Lets test-framework integrations (e.g. synclab-junit5) supply the exact test name. */
public object TestNameHolder {
    public val current: ThreadLocal<String?> = ThreadLocal()
}
