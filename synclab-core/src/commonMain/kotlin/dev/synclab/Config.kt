package dev.synclab

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * How the simulated network behaves. Immutable; build one with [NetworkConditions] `{ ... }`
 * or derive one with [copy].
 *
 * Rates are probabilities per message in `0.0..1.0`.
 */
public class NetworkConditions internal constructor(b: Builder) {
    /** One-way latency, drawn uniformly per message. */
    public val latency: ClosedRange<Duration> = b.latency
    public val dropRate: Double = b.dropRate
    public val duplicateRate: Double = b.duplicateRate
    public val responseLossRate: Double = b.responseLossRate
    public val requestFailureRate: Double = b.requestFailureRate
    public val delaySpikeRate: Double = b.delaySpikeRate
    /** Upper bound of an extra delay added by a latency spike. */
    public val delaySpike: Duration = b.delaySpike
    /** When true, messages on the same link may arrive out of order. */
    public val reorder: Boolean = b.reorder
    /** How long an RPC caller waits before failing with [SimulatedNetworkException.Timeout]. */
    public val requestTimeout: Duration = b.requestTimeout
    /** How long it takes a caller to notice the destination is unreachable. */
    public val unreachableDelay: Duration = b.unreachableDelay

    init {
        require(!latency.start.isNegative() && latency.start <= latency.endInclusive) { "latency must be a non-negative range, was $latency" }
        for ((name, rate) in listOf(
            "dropRate" to dropRate, "duplicateRate" to duplicateRate, "responseLossRate" to responseLossRate,
            "requestFailureRate" to requestFailureRate, "delaySpikeRate" to delaySpikeRate,
        )) require(rate in 0.0..1.0) { "$name must be within 0.0..1.0, was $rate" }
        require(requestTimeout.isPositive()) { "requestTimeout must be positive" }
    }

    /** Returns a copy with [block] applied. */
    public fun copy(block: Builder.() -> Unit): NetworkConditions = NetworkConditions(toBuilder().apply(block))

    public fun toBuilder(): Builder = Builder().also {
        it.latency = latency; it.dropRate = dropRate; it.duplicateRate = duplicateRate
        it.responseLossRate = responseLossRate; it.requestFailureRate = requestFailureRate
        it.delaySpikeRate = delaySpikeRate; it.delaySpike = delaySpike; it.reorder = reorder
        it.requestTimeout = requestTimeout; it.unreachableDelay = unreachableDelay
    }

    override fun toString(): String =
        "NetworkConditions(latency=$latency, drop=$dropRate, duplicate=$duplicateRate, responseLoss=$responseLossRate, " +
            "requestFailure=$requestFailureRate, spikes=$delaySpikeRate up to $delaySpike, reorder=$reorder, timeout=$requestTimeout)"

    public class Builder internal constructor() {
        public var latency: ClosedRange<Duration> = 5.milliseconds..80.milliseconds
        public var dropRate: Double = 0.0
        public var duplicateRate: Double = 0.0
        public var responseLossRate: Double = 0.0
        public var requestFailureRate: Double = 0.0
        public var delaySpikeRate: Double = 0.0
        public var delaySpike: Duration = 2.seconds
        public var reorder: Boolean = false
        public var requestTimeout: Duration = 5.seconds
        public var unreachableDelay: Duration = 10.milliseconds
    }

    public companion object {
        /** No faults: fixed small latency, FIFO links. Useful for a baseline. */
        public val Perfect: NetworkConditions = NetworkConditions { latency = 10.milliseconds..10.milliseconds }

        /** A realistic mobile network: some loss, duplicates, reordering and slow responses. The default. */
        public val Flaky: NetworkConditions = NetworkConditions {
            latency = 5.milliseconds..150.milliseconds
            dropRate = 0.03
            duplicateRate = 0.03
            responseLossRate = 0.03
            requestFailureRate = 0.02
            delaySpikeRate = 0.02
            delaySpike = 3.seconds
            reorder = true
        }

        /** Aggressive settings for flushing out rare bugs quickly. */
        public val Hostile: NetworkConditions = Flaky.copy {
            latency = 1.milliseconds..400.milliseconds
            dropRate = 0.12
            duplicateRate = 0.12
            responseLossRate = 0.12
            requestFailureRate = 0.08
            delaySpikeRate = 0.08
            delaySpike = 8.seconds
        }
    }
}

/** Builds [NetworkConditions] from fault-free defaults: 5..80 ms latency, FIFO links, no loss. */
public fun NetworkConditions(block: NetworkConditions.Builder.() -> Unit): NetworkConditions =
    NetworkConditions(NetworkConditions.Builder().apply(block))

/**
 * Background "chaos" applied while the scenario runs: random disconnects and crashes of nodes
 * that allow them. Stops automatically when the simulation starts settling.
 */
public class Chaos private constructor(
    public val disconnectEvery: ClosedRange<Duration>?,
    public val disconnectFor: ClosedRange<Duration>,
    public val crashEvery: ClosedRange<Duration>?,
    public val crashFor: ClosedRange<Duration>,
) {
    public class Builder internal constructor() {
        internal var disconnectEvery: ClosedRange<Duration>? = null
        internal var disconnectFor: ClosedRange<Duration> = 200.milliseconds..5.seconds
        internal var crashEvery: ClosedRange<Duration>? = null
        internal var crashFor: ClosedRange<Duration> = 100.milliseconds..3.seconds

        /** Every so often, cut one random node off the network for a while. */
        public fun disconnects(every: ClosedRange<Duration>, lasting: ClosedRange<Duration> = disconnectFor) {
            disconnectEvery = every; disconnectFor = lasting
        }

        /** Every so often, crash one random crashable node and restart it later. */
        public fun crashes(every: ClosedRange<Duration>, downFor: ClosedRange<Duration> = crashFor) {
            crashEvery = every; crashFor = downFor
        }

        internal fun build() = Chaos(disconnectEvery, disconnectFor, crashEvery, crashFor)
    }

    public companion object {
        public val None: Chaos = Chaos(null, Duration.ZERO..Duration.ZERO, null, Duration.ZERO..Duration.ZERO)
    }
}

/**
 * Run-level settings. Build with `SimConfig { ... }`. Network conditions can also be changed
 * while a scenario runs via [SimNetwork.conditions].
 */
public class SimConfig private constructor(b: Builder) {
    public val network: NetworkConditions = b.network
    /** Each node's wall clock is offset by a seeded random amount within ±clockSkew. */
    public val clockSkew: Duration = b.clockSkew
    public val chaos: Chaos = b.chaos
    /** Fault types that are switched off for this run. */
    public val disabledFaults: Set<FaultType> = b.disabledFaults.toSet()
    /** After the scenario body returns, how long the healed system may take to satisfy `eventually` checks. */
    public val settleTimeout: Duration = b.settleTimeout
    public val maxVirtualTime: Duration = b.maxVirtualTime
    public val maxSteps: Long = b.maxSteps
    public val maxWallTime: Duration = b.maxWallTime
    /**
     * Re-run with the same seed and compare traces to catch nondeterminism in the code under test.
     * `simulate` checks its single run; `explore` checks the first seed and every 10th seed.
     */
    public val verifyDeterminism: Boolean = b.verifyDeterminism
    /** Fail the run when a `serve` handler throws (the caller also receives [SimulatedNetworkException.Remote]). */
    public val failOnHandlerException: Boolean = b.failOnHandlerException
    /** Number of most recent trace events kept in memory and reports. The fingerprint always covers all events. */
    public val traceLimit: Int = b.traceLimit

    public fun copy(block: Builder.() -> Unit): SimConfig = SimConfig(toBuilder().apply(block))

    public fun toBuilder(): Builder = Builder().also {
        it.network = network; it.clockSkew = clockSkew; it.chaos = chaos
        it.disabledFaults = disabledFaults.toMutableSet(); it.settleTimeout = settleTimeout
        it.maxVirtualTime = maxVirtualTime; it.maxSteps = maxSteps; it.maxWallTime = maxWallTime
        it.verifyDeterminism = verifyDeterminism; it.failOnHandlerException = failOnHandlerException
        it.traceLimit = traceLimit
    }

    public fun isEnabled(fault: FaultType): Boolean = fault !in disabledFaults

    public class Builder internal constructor() {
        public var network: NetworkConditions = NetworkConditions.Flaky
        public var clockSkew: Duration = Duration.ZERO
        public var chaos: Chaos = Chaos.None
        public var disabledFaults: MutableSet<FaultType> = mutableSetOf()
        public var settleTimeout: Duration = 60.seconds
        public var maxVirtualTime: Duration = 24.hours
        public var maxSteps: Long = 5_000_000
        public var maxWallTime: Duration = 60.seconds
        public var verifyDeterminism: Boolean = true
        public var failOnHandlerException: Boolean = true
        public var traceLimit: Int = 20_000

        /** Adjust the network starting from the current value (Flaky by default). */
        public fun network(block: NetworkConditions.Builder.() -> Unit) {
            network = network.copy(block)
        }

        /** Configure background chaos. */
        public fun chaos(block: Chaos.Builder.() -> Unit) {
            chaos = Chaos.Builder().apply(block).build()
        }

        /** Switch fault types off. */
        public fun disable(vararg faults: FaultType) {
            disabledFaults.addAll(faults)
        }

        /** Switch off every built-in fault type except [faults]. */
        public fun only(vararg faults: FaultType) {
            disabledFaults = (FaultType.builtIn - faults.toSet()).toMutableSet()
        }
    }

    public companion object {
        public val Default: SimConfig = SimConfig(Builder())
    }
}

/** Builds a [SimConfig]. Defaults: [NetworkConditions.Flaky], no chaos, no clock skew. */
public fun SimConfig(block: SimConfig.Builder.() -> Unit = {}): SimConfig = SimConfig.Default.copy(block)
