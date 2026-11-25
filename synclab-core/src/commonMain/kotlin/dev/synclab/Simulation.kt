package dev.synclab

import dev.synclab.internal.SimDispatcher
import dev.synclab.internal.brieflyWaitForForeignWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The world a scenario runs in: virtual time, nodes, the network, and the checks to enforce.
 *
 * You get one as the receiver of [simulate] and [explore]. Create all state *inside* the scenario
 * block so every run starts fresh.
 */
public class Simulation internal constructor(
    public val seed: Long,
    public val config: SimConfig,
) {
    internal val dispatcher = SimDispatcher(streamFor("scheduler"), shuffle = config.isEnabled(FaultType.INTERLEAVING))
    internal val recorder = Recorder(config.traceLimit)
    internal val rootJob: CompletableJob = SupervisorJob()
    private val rootScope = CoroutineScope(
        dispatcher + rootJob + CoroutineName("synclab") + CoroutineExceptionHandler { _, e -> onUncaught(e, null) },
    )

    /** The simulated network. */
    public val network: SimNetwork = SimNetwork(this)

    /** Seeded random stream for generating workloads in the scenario. */
    public val random: Random = streamFor("scenario")

    private val nodes = LinkedHashMap<String, SimNode<*>>()
    private val always = ArrayList<Pair<String, () -> Boolean>>()
    private val alwaysDetail = HashMap<String, () -> String>()
    private val eventually = ArrayList<Pair<String, () -> Boolean>>()
    private val eventuallyDetail = HashMap<String, () -> String>()

    internal var violation: Violation? = null
        private set

    internal var settling: Boolean = false
        private set

    /** Current virtual time since the start of the run. */
    public val now: Duration get() = dispatcher.now.milliseconds

    internal val nowMillis: Long get() = dispatcher.now

    // ------------------------------------------------------------------ nodes

    /**
     * Adds a node. [factory] builds the node's application object; it runs again after each restart,
     * so register `serve`/`onMessage` handlers and background loops inside it.
     */
    public fun <T> node(
        name: String,
        crashable: Boolean = true,
        disconnectable: Boolean = true,
        factory: NodeScope.() -> T,
    ): SimNode<T> {
        require(name.isNotBlank()) { "Node name must not be blank" }
        require(name !in nodes) { "A node named '$name' already exists" }
        val node = SimNode(this, name, crashable, disconnectable, factory)
        nodes[name] = node
        node.start()
        return node
    }

    /** All nodes, in creation order. */
    public val allNodes: List<SimNode<*>> get() = nodes.values.toList()

    internal fun nodeUp(name: String): Boolean = nodes[name]?.isUp ?: false

    internal fun nodeScope(name: String): NodeScope? {
        val n = nodes[name] ?: return null
        return n.currentScope()
    }

    // ------------------------------------------------------------------ checks

    /**
     * Registers an invariant that must hold after **every** step of the simulation.
     * Return false (or throw) to fail. Keep it side-effect free.
     * Nodes that are down are skipped automatically if you access them via [SimNode.app].
     */
    public fun always(name: String, check: () -> Boolean) {
        always += name to check
    }

    /**
     * Like [always], but compares two values after every step and shows both when they differ.
     * Ideal for "cached aggregate equals recomputed value" style invariants.
     */
    public fun <V> alwaysEqual(name: String, expected: () -> V, actual: () -> V) {
        always += name to { expected() == actual() }
        alwaysDetail[name] = { explainMismatch(expected(), actual()) }
    }

    /**
     * Registers a condition that must become true once the scenario is done and the network has been
     * healed, within [SimConfig.settleTimeout]. This is how you assert convergence.
     */
    public fun eventually(name: String, check: () -> Boolean) {
        eventually += name to check
    }

    /**
     * Like [eventually], but compares two values and reports both when they differ,
     * e.g. `eventuallyEqual("server has every expense", { phone.app.expenses }, { server.app.expenses })`.
     */
    public fun <V> eventuallyEqual(name: String, expected: () -> V, actual: () -> V) {
        eventually += name to { expected() == actual() }
        eventuallyDetail[name] = { explainMismatch(expected(), actual()).prependIndent("  ") }
    }

    /**
     * Asserts that all [replicas] become equal after the scenario completes and the network heals.
     * The failure message shows which replicas differ and how.
     */
    public fun <S> converges(name: String = "replicas converge", replicas: () -> List<Pair<String, S>>) {
        eventually += name to {
            val values = replicas()
            values.all { it.second == values.first().second }
        }
        eventuallyDetail[name] = { explainDifferences(replicas()) }
    }

    // ------------------------------------------------------------------ scenario helpers

    /** Waits [duration] of virtual time. */
    public suspend fun sleep(duration: Duration) {
        delay(duration)
    }

    /** Runs [blocks] concurrently and waits for all of them. */
    public suspend fun parallel(vararg blocks: suspend () -> Unit) {
        coroutineScope { blocks.forEach { b -> launch { b() } } }
    }

    /** Runs [block] in the background after [delay]. */
    public fun after(delay: Duration, block: suspend () -> Unit): Job = rootScope.launch {
        delay(delay)
        block()
    }

    /** Suspends until [condition] is true, polling every [poll], failing after [timeout]. */
    public suspend fun waitUntil(timeout: Duration, poll: Duration = 10.milliseconds, condition: () -> Boolean) {
        val deadline = nowMillis + timeout.inWholeMilliseconds
        while (!condition()) {
            if (nowMillis >= deadline) throw AssertionError("Condition not met within $timeout")
            delay(poll)
        }
    }

    /** A labelled step in the trace. Exceptions propagate. */
    public suspend fun <R> step(label: String, block: suspend () -> R): R {
        record(EventKind.OP_START, null, detail = label)
        return try {
            block().also { record(EventKind.OP_END, null, detail = label) }
        } catch (e: Throwable) {
            record(EventKind.OP_FAILED, null, detail = "$label: ${e::class.simpleName}")
            throw e
        }
    }

    /** Adds a note to the trace. */
    public fun note(text: String) {
        record(EventKind.NOTE, null, detail = text)
    }

    // ------------------------------------------------------------------ internals

    internal fun faultEnabled(fault: FaultType): Boolean = config.isEnabled(fault)

    internal fun streamFor(purpose: String): Random = Random(mix(seed, Recorder.fnv(purpose)))

    internal fun record(
        kind: EventKind,
        node: String?,
        peer: String? = null,
        messageId: Long? = null,
        fault: FaultType? = null,
        detail: String = "",
    ): TraceEvent = recorder.record(dispatcher.now, kind, node, peer, messageId, fault, detail)

    internal fun onUncaught(e: Throwable, node: String?) {
        if (e is CancellationException) return
        fail(Violation(ViolationKind.UNCAUGHT, "${e::class.simpleName ?: "Exception"} in ${node ?: "background coroutine"}",
            e.message ?: e.toString(), nowMillis, node, e))
    }

    internal fun failConfig(message: String, node: String?) {
        fail(Violation(ViolationKind.UNCAUGHT, "Misconfigured simulation", message, nowMillis, node))
    }

    internal fun fail(v: Violation) {
        if (violation != null) return
        violation = v
        record(EventKind.VIOLATION, v.node, detail = "${v.name}: ${describe(v.message, 200)}")
    }

    private fun checkAlways() {
        for ((name, check) in always) {
            val result = try {
                check()
            } catch (e: NodeDownException) {
                true
            } catch (e: Throwable) {
                fail(Violation(ViolationKind.INVARIANT, name, e.message ?: e.toString(), nowMillis, cause = e))
                return
            }
            if (!result) {
                val detail = alwaysDetail[name]?.let { d -> runCatching(d).getOrNull() }
                fail(Violation(ViolationKind.INVARIANT, name, detail ?: "Returned false right after event #${recorder.total - 1} (the last event below).", nowMillis))
                return
            }
        }
    }

    /** Names of eventually-checks that do not hold yet, with explanations. */
    private fun pendingEventually(): List<Pair<String, String>> = eventually.mapNotNull { (name, check) ->
        val ok = try {
            check()
        } catch (e: Throwable) {
            return@mapNotNull name to (e.message ?: e.toString())
        }
        if (ok) null else name to (eventuallyDetail[name]?.let { d -> runCatching(d).getOrElse { "" } } ?: "")
    }

    private val wallStart = TimeSource.Monotonic.markNow()

    /** Drives the simulation. Returns stats; [violation] is set when something failed. */
    internal fun execute(scenario: suspend Simulation.() -> Unit): RunStats {
        val main = rootScope.launch(CoroutineName("scenario")) {
            try {
                scenario()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(Violation(ViolationKind.ASSERTION, e::class.simpleName ?: "Exception", e.message ?: e.toString(), nowMillis, cause = e))
            }
        }
        val chaos = startChaos()

        // Phase 1: run the scenario body.
        runUntil { main.isCompleted }
        chaos?.cancel()
        if (violation == null && !main.isCompleted) {
            fail(if (dispatcher.nextTime() == null) describeStuck() else describeTimeBudget())
        }

        // Phase 2: heal and settle, then check eventually/converges.
        if (violation == null) settle()

        // Phase 3: clean up everything still running.
        rootJob.cancel()
        var drain = 0
        while (drain++ < 100_000 && dispatcher.nextTime().let { it != null && it <= dispatcher.now }) dispatcher.runNext()

        return RunStats(
            seed = seed,
            virtualMillis = dispatcher.now,
            steps = dispatcher.executed,
            events = recorder.total,
            messages = network.messagesSent,
            wallMillis = wallStart.elapsedNow().inWholeMilliseconds,
            fingerprint = recorder.fingerprint,
        )
    }

    /** Runs tasks until [done] or a violation. Returns false if the queue ran dry first. */
    private fun runUntil(deadlineMillis: Long = config.maxVirtualTime.inWholeMilliseconds, onTimeAdvance: (() -> Boolean)? = null, done: () -> Boolean): Boolean {
        var lastTime = dispatcher.now
        var checks = 0
        while (violation == null && !done()) {
            dispatcher.foreignDispatch?.let { thread ->
                fail(Violation(ViolationKind.ESCAPED_DISPATCHER, "Work escaped the simulation",
                    "Code resumed onto the simulation from thread '$thread'. Virtual time and ordering only work when the code " +
                        "under test uses the simulation's dispatcher. Inject your CoroutineDispatcher/CoroutineScope instead of " +
                        "using Dispatchers.IO/Default, real timers or callbacks from other threads.", nowMillis))
                return true
            }
            val next = dispatcher.nextTime()
            if (next == null) {
                brieflyWaitForForeignWork()
                if (dispatcher.foreignDispatch != null) continue
                return false
            }
            if (next > deadlineMillis) return false
            if (dispatcher.executed >= config.maxSteps) {
                fail(Violation(ViolationKind.BUDGET, "Step budget exceeded", "More than ${config.maxSteps} steps. " +
                    "A busy loop without delay()? Raise SimConfig.maxSteps if this is expected.", nowMillis))
                return true
            }
            if ((++checks and 1023) == 0 && wallStart.elapsedNow() > config.maxWallTime) {
                fail(Violation(ViolationKind.BUDGET, "Wall-clock budget exceeded", "Ran longer than ${config.maxWallTime} of real time.", nowMillis))
                return true
            }
            dispatcher.runNext()
            checkAlways()
            if (onTimeAdvance != null && dispatcher.now != lastTime) {
                lastTime = dispatcher.now
                if (onTimeAdvance()) return true
            }
        }
        return true
    }

    private fun settle() {
        settling = true
        network.calm = true
        network.heal()
        nodes.values.filter { !it.isUp }.forEach { it.restart() }
        record(EventKind.SETTLE, null, detail = "scenario finished; network healed, faults off, waiting up to ${config.settleTimeout}")
        if (eventually.isEmpty()) {
            // Still let in-flight work finish so `always` invariants see the tail of the run.
            val until = dispatcher.now + 1_000
            runUntil(until) { false }
            return
        }
        var satisfied = pendingEventually().isEmpty()
        if (satisfied || violation != null) return
        val deadline = dispatcher.now + config.settleTimeout.inWholeMilliseconds
        val ranOut = !runUntil(deadline, onTimeAdvance = { pendingEventually().isEmpty().also { satisfied = it } }) { satisfied }
        if (violation != null) return
        if (!satisfied) satisfied = pendingEventually().isEmpty()
        if (satisfied) return
        val pending = pendingEventually()
        val why = if (ranOut && dispatcher.nextTime() == null) {
            "The system went idle (nothing left to run) without getting there: nothing will retry or resync."
        } else {
            "Still not true ${config.settleTimeout} after the network was healed."
        }
        fail(Violation(ViolationKind.NOT_CONVERGED, pending.joinToString(", ") { it.first },
            why + if (pending.size == 1) {
                pending.single().second.let { d -> if (d.isBlank()) "" else "\n" + d.trimIndent().prependIndent("  ") }
            } else {
                pending.joinToString("") { (n, d) -> if (d.isBlank()) "" else "\n$n:\n$d" }
            }, nowMillis))
    }

    private fun startChaos(): Job? {
        val chaos = config.chaos
        if (chaos.disconnectEvery == null && chaos.crashEvery == null) return null
        val r = streamFor("chaos")
        return rootScope.launch(CoroutineName("chaos")) {
            chaos.disconnectEvery?.let { every ->
                launch {
                    while (true) {
                        delay(pick(r, every))
                        val candidates = nodes.values.filter { it.disconnectable && it.isUp }
                        val target = if (candidates.isEmpty()) null else candidates[r.nextInt(candidates.size)]
                        val downFor = pick(r, chaos.disconnectFor)
                        if (target != null) {
                            network.disconnect(target.name)
                            launch { delay(downFor); network.reconnect(target.name) }
                        }
                    }
                }
            }
            chaos.crashEvery?.let { every ->
                launch {
                    while (true) {
                        delay(pick(r, every))
                        val candidates = nodes.values.filter { it.crashable && it.isUp }
                        val target = if (candidates.isEmpty()) null else candidates[r.nextInt(candidates.size)]
                        val downFor = pick(r, chaos.crashFor)
                        if (target != null) {
                            target.crash()
                            launch { delay(downFor); target.restart() }
                        }
                    }
                }
            }
        }
    }

    private fun pick(r: Random, range: ClosedRange<Duration>): Duration {
        val lo = range.start.inWholeMilliseconds
        val hi = range.endInclusive.inWholeMilliseconds
        return (if (hi <= lo) lo else r.nextLong(lo, hi + 1)).milliseconds
    }

    internal fun describeStuck(): Violation = Violation(
        ViolationKind.STUCK, "Scenario never finished",
        "Nothing was left to run at t=${formatMillis(nowMillis)} but the scenario body had not returned. " +
            "Common causes: awaiting a reply that was lost without a timeout, a deadlock between coroutines, " +
            "or waiting on work running outside SyncLab (real threads, Dispatchers.IO, platform callbacks).",
        nowMillis,
    )

    internal fun describeTimeBudget(): Violation = Violation(
        ViolationKind.BUDGET, "Virtual time budget exceeded",
        "The scenario body was still running after ${config.maxVirtualTime} of virtual time. " +
            "An endless retry loop? Raise SimConfig.maxVirtualTime if this is expected.",
        config.maxVirtualTime.inWholeMilliseconds,
    )
}


private fun mix(seed: Long, salt: Long): Long {
    // SplitMix64 finaliser over seed ^ salt: independent, well-distributed streams per purpose.
    var z = seed xor salt
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

private fun explainDifferences(replicas: List<Pair<String, Any?>>): String {
    if (replicas.isEmpty()) return "  (no replicas)"
    val (refName, ref) = replicas.first()
    return buildString {
        for ((name, value) in replicas.drop(1)) {
            if (value == ref) continue
            appendLine("  $refName vs $name:")
            append(diff(ref, value).prependIndent("    "))
            appendLine()
        }
    }.trimEnd()
}

private fun explainMismatch(expected: Any?, actual: Any?): String {
    val structured = expected is Map<*, *> && actual is Map<*, *> || expected is Set<*> && actual is Set<*> || expected is List<*> && actual is List<*>
    return if (structured) {
        diff(expected, actual, "expected", "actual") + "\nexpected: ${describe(expected, 200)}\nactual:   ${describe(actual, 200)}"
    } else {
        "expected: ${describe(expected, 300)}\nactual:   ${describe(actual, 300)}"
    }
}

private fun diff(a: Any?, b: Any?, first: String = "first", second: String = "second"): String = when {
    a is Map<*, *> && b is Map<*, *> -> buildString {
        val onlyA = a.keys - b.keys
        val onlyB = b.keys - a.keys
        val changed = a.keys.intersect(b.keys).filter { a[it] != b[it] }
        if (onlyA.isNotEmpty()) appendLine("only in $first: ${onlyA.take(5).joinToString { describe(it, 60) }}${more(onlyA.size)}")
        if (onlyB.isNotEmpty()) appendLine("only in $second: ${onlyB.take(5).joinToString { describe(it, 60) }}${more(onlyB.size)}")
        changed.take(5).forEach { k -> appendLine("${describe(k, 60)}: ${describe(a[k], 80)}  vs  ${describe(b[k], 80)}") }
        if (changed.size > 5) appendLine("…and ${changed.size - 5} more differing keys")
    }.trimEnd()
    a is Set<*> && b is Set<*> -> buildString {
        val onlyA = a - b
        val onlyB = b - a
        if (onlyA.isNotEmpty()) appendLine("only in $first: ${onlyA.take(5).joinToString { describe(it, 60) }}${more(onlyA.size)}")
        if (onlyB.isNotEmpty()) appendLine("only in $second: ${onlyB.take(5).joinToString { describe(it, 60) }}${more(onlyB.size)}")
    }.trimEnd()
    a is List<*> && b is List<*> -> {
        val i = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: a.size
        "sizes ${a.size} vs ${b.size}; first difference at index $i: ${describe(a.getOrNull(i), 80)}  vs  ${describe(b.getOrNull(i), 80)}"
    }
    else -> "${describe(a, 200)}\nvs\n${describe(b, 200)}"
}

private fun more(n: Int) = if (n > 5) " (+${n - 5} more)" else ""
