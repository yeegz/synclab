package dev.synclab

/** What happened in a [TraceEvent]. */
public enum class EventKind {
    /** Message handed to the network. */
    SEND,
    /** Message arrived at its destination. */
    DELIVER,
    /** Response arrived back at the RPC caller. */
    REPLY,
    /** Message lost (random loss, partition, crashed node, response loss, interceptor). */
    DROP,
    /** Extra copy of a message scheduled. */
    DUPLICATE,
    /** RPC failed without reaching the receiver. */
    FAIL,
    /** Caller could not reach the destination. */
    UNREACHABLE,
    /** RPC caller gave up waiting. */
    TIMEOUT,
    /** Receiver's handler threw. */
    HANDLER_ERROR,
    DISCONNECT, RECONNECT, PARTITION, HEAL,
    CRASH, RESTART,
    CLOCK,
    /** A labelled operation started (`node.perform("…")` or `step("…")`). */
    OP_START,
    OP_END,
    OP_FAILED,
    /** Free-form note from the scenario. */
    NOTE,
    /** A fault was requested but its type is disabled for this run. */
    SUPPRESSED,
    /** The scenario finished and the system started settling. */
    SETTLE,
    /** An invariant or assertion failed. */
    VIOLATION,
}

/**
 * One entry of a simulation trace.
 *
 * @property index position in the full trace (0-based), stable across replays of the same seed.
 * @property timeMillis virtual time.
 * @property node the node where it happened, if any.
 * @property peer the other side of a message, if any.
 * @property messageId correlates SEND/DELIVER/REPLY/DROP of one message.
 * @property fault set when this event is an injected fault.
 */
public class TraceEvent(
    public val index: Long,
    public val timeMillis: Long,
    public val kind: EventKind,
    public val node: String?,
    public val peer: String?,
    public val messageId: Long?,
    public val fault: FaultType?,
    public val detail: String,
) {
    /** Canonical one-line form used for fingerprints and text reports. */
    public fun canonical(): String = buildString {
        append(timeMillis).append('|').append(kind.name).append('|').append(node ?: "-").append('|')
        append(peer ?: "-").append('|').append(messageId ?: "-").append('|').append(fault?.id ?: "-").append('|').append(detail)
    }

    override fun toString(): String = formatLine()

    public fun formatLine(): String = buildString {
        append("t=").append(formatMillis(timeMillis).padStart(9)).append("  ")
        val where = when {
            node != null && peer != null && kind in arrowKindsOut -> "$node -> $peer"
            node != null && peer != null -> "$node <- $peer"
            node != null -> node
            else -> "sim"
        }
        append(where.padEnd(22)).append(' ')
        append(kind.name.lowercase().padEnd(13))
        append(detail)
        if (messageId != null) append("  #").append(messageId)
        if (fault != null) append("  [").append(fault.id).append(']')
    }

    private companion object {
        val arrowKindsOut = setOf(EventKind.SEND, EventKind.DROP, EventKind.DUPLICATE, EventKind.FAIL, EventKind.UNREACHABLE, EventKind.TIMEOUT)
    }
}

internal fun formatMillis(ms: Long): String = when {
    ms < 10_000 -> "${ms}ms"
    ms < 600_000 -> "${ms / 1000}.${((ms % 1000) / 10).toString().padStart(2, '0')}s"
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

/** Ring buffer of recent events plus a rolling fingerprint of the whole trace. */
internal class Recorder(private val limit: Int) {
    private val buffer = ArrayDeque<TraceEvent>()
    var total: Long = 0
        private set
    var fingerprint: Long = FNV_OFFSET
        private set
    /** Per-event hashes for locating the first divergence between two runs (capped). */
    val eventHashes = ArrayList<Long>()
    val faultsSeen = LinkedHashSet<FaultType>()

    fun record(time: Long, kind: EventKind, node: String?, peer: String?, messageId: Long?, fault: FaultType?, detail: String): TraceEvent {
        val e = TraceEvent(total++, time, kind, node, peer, messageId, fault, detail)
        val h = fnv(e.canonical())
        fingerprint = (fingerprint xor h) * FNV_PRIME
        if (eventHashes.size < MAX_HASHES) eventHashes.add(h)
        if (fault != null && kind != EventKind.SUPPRESSED) faultsSeen.add(fault)
        buffer.addLast(e)
        if (buffer.size > limit) buffer.removeFirst()
        return e
    }

    fun events(): List<TraceEvent> = buffer.toList()
    val dropped: Long get() = total - buffer.size

    companion object {
        const val FNV_OFFSET = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
        const val MAX_HASHES = 2_000_000

        fun fnv(s: String): Long {
            var h = FNV_OFFSET
            for (c in s) {
                h = h xor c.code.toLong()
                h *= FNV_PRIME
            }
            return h
        }
    }
}

private val identityHash = Regex("@[0-9a-f]{4,}\\b")

/** toString without JVM identity hashes (which differ between runs) and capped in length. */
internal fun describe(value: Any?, max: Int = 160): String {
    if (value === Unit) return "ok"
    val raw = try {
        value.toString()
    } catch (e: Throwable) {
        "<toString failed: ${e::class.simpleName}>"
    }
    val clean = identityHash.replace(raw, "@…")
    return if (clean.length <= max) clean else clean.take(max - 1) + "…"
}
