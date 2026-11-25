package dev.synclab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration

/**
 * A node's wall clock. Virtual time is shared by the whole simulation; each node's *wall clock*
 * can be offset (clock skew) and jump (NTP correction). Give this to code that reads "now".
 */
public class SimClock internal constructor(
    private val sim: Simulation,
    private val node: String,
    offsetMillis: Long,
) {
    /** Current offset from true simulation time. */
    public var offsetMillis: Long = offsetMillis
        private set

    /** Milliseconds since the Unix epoch as this node believes it to be. Simulations start at 2026-01-01T00:00:00Z. */
    public fun nowMillis(): Long = EPOCH_START + sim.nowMillis + offsetMillis

    /** Jumps this clock by [by] (positive or negative). A [FaultType.CLOCK_SKEW] fault. */
    public fun jump(by: Duration) {
        if (!sim.faultEnabled(FaultType.CLOCK_SKEW)) {
            sim.record(EventKind.SUPPRESSED, node, detail = "clock jump $by", fault = FaultType.CLOCK_SKEW)
            return
        }
        offsetMillis += by.inWholeMilliseconds
        sim.record(EventKind.CLOCK, node, detail = "clock jumped by $by (offset now ${offsetMillis}ms)", fault = FaultType.CLOCK_SKEW)
    }

    public companion object {
        /** 2026-01-01T00:00:00Z. */
        public const val EPOCH_START: Long = 1_767_225_600_000L
    }
}

/**
 * Storage that survives [SimNode.crash]. Everything else a node holds in memory is lost on a crash.
 * Use it to model what your app persists to disk (an outbox, a local database, a sync cursor).
 */
public class DurableStorage internal constructor() {
    private val map = LinkedHashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(key: String): T? = map[key] as T?

    public operator fun set(key: String, value: Any?) {
        map[key] = value
    }

    /** Returns the stored value or stores and returns [default]. */
    @Suppress("UNCHECKED_CAST")
    public fun <T> getOrPut(key: String, default: () -> T): T = map.getOrPut(key, default) as T

    public fun remove(key: String) {
        map.remove(key)
    }

    public val keys: Set<String> get() = map.keys.toSet()
}

/**
 * What node code can do: launch background work, talk to the network, read its clock and storage.
 * It is also the node's [CoroutineScope]; everything launched here is cancelled when the node crashes.
 */
public class NodeScope internal constructor(
    public val node: SimNode<*>,
    override val coroutineContext: CoroutineContext,
) : CoroutineScope {
    public val name: String get() = node.name
    public val clock: SimClock get() = node.clock
    public val storage: DurableStorage get() = node.storage

    /** Seeded random stream for this node. Use it instead of `Random.Default`. */
    public val random: Random get() = node.random

    internal val sim: Simulation get() = node.sim

    /** Handles RPC requests sent to this node on [service]. Re-register in the node factory after restarts. */
    public fun <Req, Res> serve(service: String = DEFAULT_SERVICE, handler: suspend (from: String, request: Req) -> Res) {
        @Suppress("UNCHECKED_CAST")
        sim.network.registerService(name, service, handler as suspend (String, Any?) -> Any?)
    }

    /** A client for calling [service] on node [to]. */
    public fun <Req, Res> rpc(to: String, service: String = DEFAULT_SERVICE, timeout: Duration? = null): RpcClient<Req, Res> =
        RpcClient(sim.network, name, to, service, timeout)

    /** Sends a one-way message. Never throws; the message may be lost, duplicated or delayed. */
    public fun send(to: String, message: Any?) {
        sim.network.send(name, to, message)
    }

    /** Receives one-way messages sent to this node. */
    public fun <T> onMessage(handler: suspend (from: String, message: T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        sim.network.registerListener(name, handler as suspend (String, Any?) -> Unit)
    }

    /** Runs [block] every [period] (first run after [initialDelay]) until the node crashes or the simulation ends. */
    public fun every(period: Duration, initialDelay: Duration = period, block: suspend () -> Unit): Job = launch {
        delay(initialDelay)
        while (isActive) {
            block()
            delay(period)
        }
    }

    public companion object {
        public const val DEFAULT_SERVICE: String = "default"
    }
}

/** Typed RPC client. Calling it suspends until the reply arrives or throws [SimulatedNetworkException]. */
public class RpcClient<Req, Res> internal constructor(
    private val network: SimNetwork,
    public val from: String,
    public val to: String,
    public val service: String,
    private val timeout: Duration?,
) {
    @Suppress("UNCHECKED_CAST")
    public suspend operator fun invoke(request: Req): Res = network.call(from, to, service, request, timeout) as Res
}

/**
 * A simulated device or server. Its application object [app] is built by the factory you pass to
 * [Simulation.node], and rebuilt by the same factory after a crash and restart.
 */
public class SimNode<out T> internal constructor(
    internal val sim: Simulation,
    public val name: String,
    /** Whether background chaos may crash this node. */
    public val crashable: Boolean,
    /** Whether background chaos may disconnect this node. */
    public val disconnectable: Boolean,
    private val factory: NodeScope.() -> T,
) {
    public val storage: DurableStorage = DurableStorage()
    public val clock: SimClock
    public val random: Random = sim.streamFor("node:$name")

    private var scope: CoroutineScope? = null
    private var nodeScope: NodeScope? = null

    internal fun currentScope(): NodeScope? = if (isUp) nodeScope else null
    private var current: Any? = null
    private var hasApp = false

    /** False while crashed. */
    public var isUp: Boolean = false
        private set

    /** How many times this node has restarted after a crash. */
    public var restarts: Int = 0
        private set

    init {
        val skew = sim.config.clockSkew.inWholeMilliseconds
        val clockRandom = sim.streamFor("clock:$name")
        val drawn = if (skew > 0) clockRandom.nextLong(-skew, skew + 1) else 0L
        val offset = if (drawn != 0L && sim.faultEnabled(FaultType.CLOCK_SKEW)) drawn else 0L
        clock = SimClock(sim, name, offset)
        if (offset != 0L) sim.record(EventKind.CLOCK, name, detail = "clock offset ${offset}ms", fault = FaultType.CLOCK_SKEW)
    }

    /** The application object. Throws [NodeDownException] while the node is crashed. */
    @Suppress("UNCHECKED_CAST")
    public val app: T
        get() {
            if (!isUp || !hasApp) throw NodeDownException(name, "Node '$name' is crashed. Check isUp, use appOrNull, or restart() it.")
            return current as T
        }

    /** The application object, or null while crashed. */
    @Suppress("UNCHECKED_CAST")
    public val appOrNull: T? get() = if (isUp && hasApp) current as T else null

    internal fun start() {
        val nodeScope = CoroutineScope(
            sim.dispatcher + SupervisorJob(sim.rootJob) + CoroutineName(name) + CoroutineExceptionHandler { _, e ->
                sim.onUncaught(e, name)
            },
        )
        scope = nodeScope
        isUp = true
        hasApp = false
        val ns = NodeScope(this, nodeScope.coroutineContext)
        this.nodeScope = ns
        current = ns.factory()
        hasApp = true
    }

    /** Crashes the node: in-flight work is cancelled, in-memory state is lost, messages to it are dropped. */
    public fun crash() {
        if (!isUp) return
        if (!sim.faultEnabled(FaultType.CRASH)) {
            sim.record(EventKind.SUPPRESSED, name, detail = "crash", fault = FaultType.CRASH)
            return
        }
        crashInternal("crashed")
    }

    internal fun crashInternal(reason: String) {
        isUp = false
        hasApp = false
        current = null
        sim.network.forgetNode(name)
        scope?.cancel(NodeCrash(name))
        scope = null
        nodeScope = null
        sim.record(EventKind.CRASH, name, detail = reason, fault = FaultType.CRASH)
    }

    /** Restarts a crashed node: the factory runs again with the same [storage]. No-op when up. */
    public fun restart() {
        if (isUp) return
        restarts++
        sim.record(EventKind.RESTART, name, detail = "restart #$restarts")
        start()
    }

    /**
     * Runs [block] on this node (inside its scope) and returns the result. If the node crashes while
     * the block runs, throws [NodeDownException] to the caller. The operation is labelled in the trace.
     */
    public suspend fun <R> perform(label: String, block: suspend T.() -> R): R {
        val s = scope
        val target = appOrNull
        if (s == null || target == null) throw NodeDownException(name, "Cannot perform '$label': node '$name' is crashed.")
        sim.record(EventKind.OP_START, name, detail = label)
        val result = CompletableDeferred<R>()
        s.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                result.complete(target.block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                result.completeExceptionally(NodeDownException(name, "Node '$name' crashed during '$label'."))
                throw e
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return try {
            result.await().also { sim.record(EventKind.OP_END, name, detail = label) }
        } catch (e: Throwable) {
            sim.record(EventKind.OP_FAILED, name, detail = "$label: ${e::class.simpleName}: ${describe(e.message, 120)}")
            throw e
        }
    }

    override fun toString(): String = "SimNode($name, ${if (isUp) "up" else "down"})"
}

internal class NodeCrash(node: String) : kotlinx.coroutines.CancellationException("node $node crashed")
