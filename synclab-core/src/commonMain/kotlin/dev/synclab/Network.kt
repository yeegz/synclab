package dev.synclab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Which leg of a conversation a message is. */
public enum class MessageKind { REQUEST, RESPONSE, ONE_WAY }

/** A message as seen by a custom [MessageInterceptor]. */
public class InterceptedMessage(
    public val id: Long,
    public val kind: MessageKind,
    public val from: String,
    public val to: String,
    public val service: String?,
    public val payload: Any?,
)

/** What a [MessageInterceptor] decides to do with a message. */
public sealed class Verdict {
    /** Leave the message alone. */
    public object Pass : Verdict()
    /** Lose the message. */
    public object Drop : Verdict()
    /** Deliver an extra copy. */
    public object Duplicate : Verdict()
    /** Add extra delay. */
    public class Delay(public val extra: Duration) : Verdict()
    /** Deliver a different payload instead (e.g. corrupted or truncated). */
    public class Replace(public val payload: Any?) : Verdict()
}

/**
 * A custom fault. Return a [Verdict] for each message. Use the given [Random] for any randomness
 * so runs stay reproducible.
 */
public fun interface MessageInterceptor {
    public fun intercept(message: InterceptedMessage, random: Random): Verdict
}

/**
 * The simulated network connecting all nodes. Addresses are node names.
 *
 * Faults are applied per message according to [conditions] and the run's enabled [FaultType]s.
 * Connectivity changes ([disconnect], [partition], [heal]) take effect immediately, including for
 * messages already in flight: a message arriving across a cut link is dropped.
 */
public class SimNetwork internal constructor(private val sim: Simulation) {
    /** Default conditions for every link. Can be changed while the scenario runs. */
    public var conditions: NetworkConditions = sim.config.network

    private val linkOverrides = HashMap<Pair<String, String>, NetworkConditions>()
    private val isolated = LinkedHashSet<String>()
    private val cut = LinkedHashSet<Pair<String, String>>()
    private val services = HashMap<Pair<String, String>, suspend (String, Any?) -> Any?>()
    private val listeners = HashMap<String, MutableList<suspend (String, Any?) -> Unit>>()
    private val interceptors = ArrayList<Pair<FaultType, MessageInterceptor>>()
    /** Last scheduled arrival per link, used to keep links FIFO when reordering is off. */
    private val lastDelivery = HashMap<Pair<String, String>, dev.synclab.internal.SimDispatcher.Task>()
    private val random = sim.streamFor("network")
    private val interceptorRandom = sim.streamFor("interceptors")
    private var nextId = 1L

    /** When true (while settling), probabilistic faults are off; latency remains. */
    internal var calm: Boolean = false

    /** Messages sent so far. */
    public var messagesSent: Long = 0
        private set

    /** Messages currently travelling. */
    public var inFlight: Int = 0
        private set

    /** Overrides conditions for messages from [from] to [to] (one direction). */
    public fun link(from: String, to: String, conditions: NetworkConditions) {
        linkOverrides[from to to] = conditions
    }

    /** Applies conditions to both directions between [a] and [b]. */
    public fun linkBoth(a: String, b: String, conditions: NetworkConditions) {
        link(a, b, conditions); link(b, a, conditions)
    }

    /** Installs a custom fault. It is skipped when [fault] is disabled and while settling. */
    public fun intercept(fault: FaultType, interceptor: MessageInterceptor) {
        interceptors += fault to interceptor
    }

    /** Cuts [node] off from everyone (airplane mode). A [FaultType.PARTITION] fault. */
    public fun disconnect(node: String) {
        if (!faultAllowed(FaultType.PARTITION, node, "disconnect")) return
        if (isolated.add(node)) sim.record(EventKind.DISCONNECT, node, detail = "disconnected", fault = FaultType.PARTITION)
    }

    /** Reconnects a node cut off by [disconnect]. */
    public fun reconnect(node: String) {
        if (isolated.remove(node)) sim.record(EventKind.RECONNECT, node, detail = "reconnected")
    }

    /** Splits nodes into groups that cannot talk to each other. Nodes not listed are unaffected. */
    public fun partition(vararg groups: Collection<String>) {
        if (!faultAllowed(FaultType.PARTITION, null, "partition ${groups.map { it.toList() }}")) return
        for (i in groups.indices) for (j in groups.indices) if (i != j) {
            for (a in groups[i]) for (b in groups[j]) cut += a to b
        }
        sim.record(EventKind.PARTITION, null, detail = groups.joinToString(" | ") { it.joinToString(",") }, fault = FaultType.PARTITION)
    }

    /** Removes every partition and reconnects every node. */
    public fun heal() {
        if (isolated.isEmpty() && cut.isEmpty()) return
        isolated.clear(); cut.clear()
        sim.record(EventKind.HEAL, null, detail = "network healed")
    }

    /** True when a message from [from] to [to] would currently get through (ignoring random loss). */
    public fun isReachable(from: String, to: String): Boolean =
        from !in isolated && to !in isolated && (from to to) !in cut &&
            sim.nodeUp(from) && sim.nodeUp(to)

    private fun faultAllowed(fault: FaultType, node: String?, what: String): Boolean {
        if (sim.faultEnabled(fault)) return true
        sim.record(EventKind.SUPPRESSED, node, detail = what, fault = fault)
        return false
    }

    private fun conditionsFor(from: String, to: String) = linkOverrides[from to to] ?: conditions

    internal fun registerService(node: String, service: String, handler: suspend (String, Any?) -> Any?) {
        services[node to service] = handler
    }

    internal fun registerListener(node: String, handler: suspend (String, Any?) -> Unit) {
        listeners.getOrPut(node) { ArrayList() }.add(handler)
    }

    internal fun forgetNode(node: String) {
        services.keys.removeAll { it.first == node }
        listeners.remove(node)
    }

    // ---------------------------------------------------------------- transmission

    private class Fate(
        val latency: Long,
        val lost: Boolean,
        val duplicate: Boolean,
        val duplicateLatency: Long,
        val failFast: Boolean,
    )

    /**
     * Draws every random decision for one message up front, always consuming the same number of
     * values regardless of which faults are enabled. This keeps unrelated decisions stable when the
     * shrinker turns a fault type off.
     */
    private fun drawFate(c: NetworkConditions, kind: MessageKind): Fate {
        val base = draw(c.latency)
        val spikeRoll = random.nextDouble()
        val spike = (random.nextDouble() * c.delaySpike.inWholeMilliseconds).toLong()
        val lossRoll = random.nextDouble()
        val dupRoll = random.nextDouble()
        val dupLatency = draw(c.latency)
        val failRoll = random.nextDouble()

        val spiking = spikeRoll < c.delaySpikeRate && active(FaultType.DELAY)
        val lossFault = if (kind == MessageKind.RESPONSE) FaultType.RESPONSE_LOSS else FaultType.DROP
        val lossRate = if (kind == MessageKind.RESPONSE) c.responseLossRate else c.dropRate
        return Fate(
            latency = base + if (spiking) spike else 0L,
            lost = lossRoll < lossRate && active(lossFault),
            duplicate = kind != MessageKind.RESPONSE && dupRoll < c.duplicateRate && active(FaultType.DUPLICATE),
            duplicateLatency = dupLatency,
            failFast = kind == MessageKind.REQUEST && failRoll < c.requestFailureRate && active(FaultType.REQUEST_FAILURE),
        )
    }

    private fun draw(range: ClosedRange<Duration>): Long {
        val lo = range.start.inWholeMilliseconds
        val hi = range.endInclusive.inWholeMilliseconds
        return if (hi <= lo) {
            random.nextLong(); lo
        } else random.nextLong(lo, hi + 1)
    }

    private fun active(fault: FaultType) = !calm && sim.faultEnabled(fault)

    /** Schedules delivery honouring per-link FIFO unless reordering is active. */
    private fun scheduleArrival(from: String, to: String, latency: Long, c: NetworkConditions, arrive: () -> Unit) {
        var at = sim.nowMillis + latency
        val key = from to to
        val last = lastDelivery[key]?.takeIf { !it.cancelled }
        var tieBreak: Long? = null
        if (!(c.reorder && active(FaultType.REORDER))) {
            // FIFO: never arrive before the previous message on this link; at the same instant,
            // share its tie-break so the sequence number keeps them in order.
            if (last != null && at <= last.time) {
                at = last.time
                tieBreak = last.tieBreak
            }
        } else if (last != null && last.time > at) {
            // This message overtakes an earlier one on the same link.
            sim.recorder.faultsSeen.add(FaultType.REORDER)
        }
        inFlight++
        val task = sim.dispatcher.schedule(at - sim.nowMillis, Runnable {
            inFlight--
            arrive()
        }, tieBreak)
        if (last == null || task.time >= last.time) lastDelivery[key] = task
    }

    /**
     * Sends one message, applying built-in faults and interceptors. [arrive] runs for each copy that
     * reaches a live, reachable destination.
     */
    private fun transmit(
        kind: MessageKind,
        id: Long,
        from: String,
        to: String,
        service: String?,
        payload: Any?,
        fate: Fate,
        c: NetworkConditions,
        arrive: (Any?) -> Unit,
    ) {
        var message = payload
        var extraDelay = 0L
        var copies = if (fate.duplicate) 2 else 1
        var lost = fate.lost
        var lostBy: FaultType? = if (lost) (if (kind == MessageKind.RESPONSE) FaultType.RESPONSE_LOSS else FaultType.DROP) else null

        for ((fault, interceptor) in interceptors) {
            if (!active(fault)) continue
            val verdict = interceptor.intercept(InterceptedMessage(id, kind, from, to, service, message), interceptorRandom)
            when (verdict) {
                Verdict.Pass -> {}
                Verdict.Drop -> { lost = true; lostBy = fault }
                Verdict.Duplicate -> { copies++; sim.record(EventKind.DUPLICATE, from, to, id, fault, "interceptor duplicated") }
                is Verdict.Delay -> {
                    extraDelay += verdict.extra.inWholeMilliseconds
                    sim.record(EventKind.NOTE, from, to, id, fault, "interceptor delayed by ${verdict.extra}")
                }
                is Verdict.Replace -> {
                    sim.record(EventKind.NOTE, from, to, id, fault, "interceptor replaced payload with ${describe(verdict.payload)}")
                    message = verdict.payload
                }
            }
        }

        if (lost) {
            val why = if (kind == MessageKind.RESPONSE) "response lost after the request was processed" else "lost in transit"
            sim.record(EventKind.DROP, from, to, id, lostBy, why)
            return
        }
        if (fate.duplicate) sim.record(EventKind.DUPLICATE, from, to, id, FaultType.DUPLICATE, "network duplicated message")

        val delivered = message
        for (copy in 0 until copies) {
            val latency = (if (copy == 0) fate.latency else fate.duplicateLatency) + extraDelay
            scheduleArrival(from, to, latency, c) {
                if (!isReachable(from, to)) {
                    val reason = when {
                        !sim.nodeUp(to) -> "$to is down"
                        !sim.nodeUp(from) -> "$from crashed before delivery"
                        else -> "link cut while in flight"
                    }
                    sim.record(EventKind.DROP, to, from, id, FaultType.PARTITION.takeIf { sim.nodeUp(to) && sim.nodeUp(from) } ?: FaultType.CRASH, reason)
                } else {
                    arrive(delivered)
                }
            }
        }
    }

    // ---------------------------------------------------------------- RPC

    internal suspend fun call(from: String, to: String, service: String, request: Any?, timeout: Duration?): Any? {
        val id = nextId++
        messagesSent++
        val c = conditionsFor(from, to)
        val fate = drawFate(c, MessageKind.REQUEST)
        val responseFate = drawFate(conditionsFor(to, from), MessageKind.RESPONSE)
        val waitFor = timeout ?: c.requestTimeout
        val label = if (service == NodeScope.DEFAULT_SERVICE) describe(request) else "$service ${describe(request)}"
        sim.record(EventKind.SEND, from, to, id, null, label)

        if (!isReachable(from, to)) {
            sim.record(EventKind.UNREACHABLE, from, to, id, if (sim.nodeUp(to)) FaultType.PARTITION else FaultType.CRASH, "cannot reach $to")
            delay(c.unreachableDelay)
            throw SimulatedNetworkException.Unreachable("$from cannot reach $to (call #$id to '$service')")
        }
        if (fate.failFast) {
            delay(fate.latency.milliseconds)
            sim.record(EventKind.FAIL, from, to, id, FaultType.REQUEST_FAILURE, "request rejected before reaching $to")
            throw SimulatedNetworkException.RequestFailed("Request #$id from $from to $to failed before it was processed")
        }

        val reply = CompletableDeferred<Result<Any?>>()
        var executions = 0
        transmit(MessageKind.REQUEST, id, from, to, service, request, fate, c) { payload ->
            val handler = services[to to service]
            if (handler == null) {
                sim.failConfig("Node '$to' received call #$id for service '$service' but nothing serves it. " +
                    "Register it in the node factory with serve(\"$service\") { from, req -> ... }.", to)
                return@transmit
            }
            executions++
            sim.record(EventKind.DELIVER, to, from, id, null, if (executions > 1) "executing again (copy $executions)" else "executing")
            sim.nodeScope(to)?.launch(start = CoroutineStart.UNDISPATCHED) {
                val result = try {
                    Result.success(handler(from, payload))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    sim.record(EventKind.HANDLER_ERROR, to, from, id, null, "${e::class.simpleName}: ${describe(e.message)}")
                    if (sim.config.failOnHandlerException) sim.onUncaught(e, to)
                    Result.failure(SimulatedNetworkException.Remote("Handler on $to failed: ${e.message}", e))
                }
                val rc = conditionsFor(to, from)
                // Only the first execution uses the pre-drawn response fate; copies draw fresh ones.
                val rf = if (executions == 1) responseFate else drawFate(rc, MessageKind.RESPONSE)
                transmit(MessageKind.RESPONSE, id, to, from, service, result, rf, rc) {
                    if (reply.isCompleted) {
                        sim.record(EventKind.REPLY, from, to, id, null, "late reply ignored")
                    } else {
                        sim.record(EventKind.REPLY, from, to, id, null, describe(result.getOrNull() ?: result.exceptionOrNull()))
                        reply.complete(result)
                    }
                }
            }
        }

        val outcome = withTimeoutOrNull(waitFor) { reply.await() }
        if (outcome == null) {
            sim.record(EventKind.TIMEOUT, from, to, id, null, "no reply after $waitFor")
            throw SimulatedNetworkException.Timeout("Call #$id from $from to $to timed out after $waitFor. It may or may not have been processed.")
        }
        return outcome.getOrThrow()
    }

    // ---------------------------------------------------------------- one-way

    internal fun send(from: String, to: String, message: Any?) {
        val id = nextId++
        messagesSent++
        val c = conditionsFor(from, to)
        val fate = drawFate(c, MessageKind.ONE_WAY)
        sim.record(EventKind.SEND, from, to, id, null, describe(message))
        if (!isReachable(from, to)) {
            sim.record(EventKind.DROP, from, to, id, if (sim.nodeUp(to)) FaultType.PARTITION else FaultType.CRASH, "$to unreachable")
            return
        }
        transmit(MessageKind.ONE_WAY, id, from, to, null, message, fate, c) { payload ->
            val handlers = listeners[to]
            if (handlers.isNullOrEmpty()) {
                sim.record(EventKind.DROP, to, from, id, null, "no onMessage handler on $to")
                return@transmit
            }
            sim.record(EventKind.DELIVER, to, from, id, null, "received")
            val scope = sim.nodeScope(to) ?: return@transmit
            // Start handlers immediately so arrival order is processing order (like a single-threaded receiver).
            for (h in handlers.toList()) scope.launch(start = CoroutineStart.UNDISPATCHED) { h(from, payload) }
        }
    }
}
