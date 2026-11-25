package dev.synclab

/**
 * A category of injected failure.
 *
 * Every fault SyncLab injects is tagged with a type. When a run fails, SyncLab re-runs the same seed
 * with types switched off one at a time and reports the smallest set still needed to reproduce the
 * failure ("Needs: DUPLICATE, RESPONSE_LOSS").
 *
 * You can define your own types for custom interceptors: `val CORRUPT = FaultType("CORRUPT")`.
 */
public class FaultType(public val id: String) {
    init {
        require(id.isNotBlank() && id.none { it == ':' || it == '+' || it.isWhitespace() }) {
            "FaultType id must be non-blank and contain no ':', '+' or whitespace: '$id'"
        }
    }

    override fun toString(): String = id

    override fun equals(other: Any?): Boolean = other is FaultType && other.id == id

    override fun hashCode(): Int = id.hashCode()

    public companion object {
        /** A message (request or one-way) is silently lost. */
        public val DROP: FaultType = FaultType("DROP")

        /** A message is delivered twice. Requests are executed twice by the receiver. */
        public val DUPLICATE: FaultType = FaultType("DUPLICATE")

        /** Messages on the same link may overtake each other. Off = per-link FIFO. */
        public val REORDER: FaultType = FaultType("REORDER")

        /** Occasional latency spikes well beyond the normal latency range. */
        public val DELAY: FaultType = FaultType("DELAY")

        /** The receiver processed a request, but the response never reached the caller. */
        public val RESPONSE_LOSS: FaultType = FaultType("RESPONSE_LOSS")

        /** A request fails fast without reaching the receiver (e.g. HTTP 503 from a load balancer). */
        public val REQUEST_FAILURE: FaultType = FaultType("REQUEST_FAILURE")

        /** Nodes are disconnected or partitioned from each other. */
        public val PARTITION: FaultType = FaultType("PARTITION")

        /** A node crashes, losing in-memory state and in-flight work, then restarts. */
        public val CRASH: FaultType = FaultType("CRASH")

        /** Node wall clocks disagree or jump. */
        public val CLOCK_SKEW: FaultType = FaultType("CLOCK_SKEW")

        /** Events due at the same virtual instant run in a seeded random order instead of FIFO. */
        public val INTERLEAVING: FaultType = FaultType("INTERLEAVING")

        /** All built-in types, in the order the shrinker tries them. */
        public val builtIn: List<FaultType> = listOf(
            INTERLEAVING, REORDER, DELAY, REQUEST_FAILURE, DROP, DUPLICATE, RESPONSE_LOSS, CLOCK_SKEW, PARTITION, CRASH,
        )
    }
}
