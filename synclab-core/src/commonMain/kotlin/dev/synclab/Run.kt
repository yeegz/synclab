package dev.synclab

import dev.synclab.internal.guessTestName
import dev.synclab.internal.readSetting
import kotlin.time.TimeSource

/** Result of a passing [simulate] call. */
public class SimulationResult internal constructor(
    public val stats: RunStats,
    /** Fault types that actually fired during the run. */
    public val faultsSeen: Set<FaultType>,
    /** The most recent trace events (up to [SimConfig.traceLimit]). */
    public val trace: List<TraceEvent>,
    public val replayToken: String,
)

/** Result of a passing [explore] call. */
public class ExploreResult internal constructor(
    public val runs: Int,
    public val seeds: LongRange,
    public val totalSteps: Long,
    public val totalVirtualMillis: Long,
    public val wallMillis: Long,
    /** How often each fault type fired across all runs: shows what was actually exercised. */
    public val faultCoverage: Map<FaultType, Int>,
)

/**
 * Runs [scenario] once with [seed]. Throws [SimulationFailure] if an invariant breaks, the system
 * fails to converge, the scenario throws, or the code under test is nondeterministic.
 *
 * Set `SYNCLAB_REPLAY` (or pass [replay]) to re-run a reported failure exactly.
 */
public fun simulate(
    seed: Long = 1L,
    config: SimConfig = SimConfig(),
    replay: String? = null,
    scenario: suspend Simulation.() -> Unit,
): SimulationResult {
    val token = (replay ?: readSetting("synclab.replay", "SYNCLAB_REPLAY"))?.let(ReplayToken::parse)
    val runSeed = token?.seed ?: seed
    val runConfig = token?.applyTo(config) ?: config
    val testName = guessTestName()
    val start = TimeSource.Monotonic.markNow()

    val outcome = Runner.runOnce(runSeed, runConfig, scenario)
    var failure = outcome.failure
    if (failure == null && runConfig.verifyDeterminism) failure = Runner.checkDeterminism(runSeed, runConfig, scenario, outcome)
    if (failure == null && token != null && !token.matches(outcome.sim.recorder.fingerprint)) {
        failure = Runner.replayDiverged(runSeed, runConfig, outcome)
    }

    if (failure != null) {
        val report = Runner.shrinkAndReport(runSeed, runConfig, scenario, failure, testName, runsBefore = 0, shrink = token == null)
        SyncLab.notify(RunSummary(testName, 1, false, outcome.stats.steps, outcome.stats.virtualMillis, start.elapsedNow().inWholeMilliseconds, runSeed, report))
        throw SimulationFailure(report)
    }
    SyncLab.notify(RunSummary(testName, 1, true, outcome.stats.steps, outcome.stats.virtualMillis, start.elapsedNow().inWholeMilliseconds, runSeed, null))
    return SimulationResult(outcome.stats, outcome.sim.recorder.faultsSeen.toSet(), outcome.sim.recorder.events(),
        ReplayToken(runSeed, runConfig.disabledFaults, outcome.sim.recorder.fingerprint).toString())
}

/**
 * Property-based exploration: runs [scenario] once per seed in [firstSeed] until [runs] seeds have
 * passed or one fails. Use [Simulation.random] inside the scenario to generate workloads, so each
 * seed explores a different workload *and* a different fault schedule.
 *
 * Seeds are fixed by default so CI is reproducible. Environment overrides:
 * - `SYNCLAB_RUNS=5000` runs more seeds (e.g. nightly).
 * - `SYNCLAB_FIRST_SEED=…` starts somewhere else; `SYNCLAB_FIRST_SEED=random` picks a random start.
 * - `SYNCLAB_REPLAY=SL1:…` runs exactly one reported failure.
 *
 * On failure, SyncLab shrinks the set of fault types to the minimum that still reproduces the same
 * violation, and throws [SimulationFailure] with a trace and a replay token.
 */
public fun explore(
    runs: Int = 100,
    firstSeed: Long = 1L,
    config: SimConfig = SimConfig(),
    scenario: suspend Simulation.() -> Unit,
): ExploreResult {
    val replay = readSetting("synclab.replay", "SYNCLAB_REPLAY")
    val testName = guessTestName()
    val start = TimeSource.Monotonic.markNow()
    if (replay != null) {
        val r = simulate(config = config, replay = replay, scenario = scenario)
        return ExploreResult(1, r.stats.seed..r.stats.seed, r.stats.steps, r.stats.virtualMillis,
            start.elapsedNow().inWholeMilliseconds, r.faultsSeen.associateWith { 1 })
    }
    val runCount = readSetting("synclab.runs", "SYNCLAB_RUNS")?.toIntOrNull() ?: runs
    require(runCount > 0) { "runs must be positive" }
    val base = when (val s = readSetting("synclab.firstSeed", "SYNCLAB_FIRST_SEED")) {
        null -> firstSeed
        "random" -> kotlin.random.Random.nextLong(1, Long.MAX_VALUE / 2)
        else -> s.toLongOrNull() ?: firstSeed
    }

    var steps = 0L
    var virtual = 0L
    val coverage = LinkedHashMap<FaultType, Int>()
    for (i in 0 until runCount) {
        val seed = base + i
        val outcome = Runner.runOnce(seed, config, scenario)
        var failure = outcome.failure
        // Check determinism once up front: a nondeterministic scenario makes every other report unreliable.
        if (failure == null && i == 0 && config.verifyDeterminism) failure = Runner.checkDeterminism(seed, config, scenario, outcome)
        steps += outcome.stats.steps
        virtual += outcome.stats.virtualMillis
        outcome.sim.recorder.faultsSeen.forEach { coverage[it] = (coverage[it] ?: 0) + 1 }
        if (failure != null) {
            val report = Runner.shrinkAndReport(seed, config, scenario, failure, testName, runsBefore = i, shrink = true)
            SyncLab.notify(RunSummary(testName, i + 1, false, steps, virtual, start.elapsedNow().inWholeMilliseconds, base, report))
            throw SimulationFailure(report)
        }
    }
    val wall = start.elapsedNow().inWholeMilliseconds
    SyncLab.notify(RunSummary(testName, runCount, true, steps, virtual, wall, base, null))
    return ExploreResult(runCount, base until base + runCount, steps, virtual, wall, coverage)
}

internal class Outcome(val sim: Simulation, val stats: RunStats, val failure: Violation?)

internal object Runner {
    fun runOnce(seed: Long, config: SimConfig, scenario: suspend Simulation.() -> Unit): Outcome {
        val sim = Simulation(seed, config)
        val stats = sim.execute(scenario)
        return Outcome(sim, stats, sim.violation)
    }

    fun checkDeterminism(seed: Long, config: SimConfig, scenario: suspend Simulation.() -> Unit, first: Outcome): Violation? {
        val second = runOnce(seed, config, scenario)
        if (second.stats.fingerprint == first.stats.fingerprint && second.failure == null) return null
        val a = first.sim.recorder.eventHashes
        val b = second.sim.recorder.eventHashes
        val idx = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: minOf(a.size, b.size)
        val evA = first.sim.recorder.events().firstOrNull { it.index == idx.toLong() }
        val evB = second.sim.recorder.events().firstOrNull { it.index == idx.toLong() }
        return Violation(
            ViolationKind.NONDETERMINISM, "Same seed, different behaviour",
            buildString {
                appendLine("Running seed $seed twice produced different traces, first differing at event #$idx:")
                appendLine("  run 1: ${evA?.formatLine() ?: "(no event / outside retained window)"}")
                appendLine("  run 2: ${evB?.formatLine() ?: "(no event / outside retained window)"}")
                append("Typical causes: state shared between runs (create everything inside the scenario), " +
                    "Random.Default or unseeded UUIDs (use Simulation.random / NodeScope.random), real clocks " +
                    "(use NodeScope.clock), iteration over hash-ordered collections of objects without stable hashCode, " +
                    "or threads outside the simulation.")
            },
            evA?.timeMillis ?: 0L,
        )
    }

    fun replayDiverged(seed: Long, config: SimConfig, outcome: Outcome): Violation = Violation(
        ViolationKind.NONDETERMINISM, "Replay diverged",
        "The replay token's trace fingerprint does not match this run of seed $seed. The code under test or the " +
            "scenario changed since the failure was recorded (that's fine if you are fixing it), or the code is nondeterministic.",
        outcome.stats.virtualMillis,
    )

    /**
     * Greedy fault shrinking: for each fault type that fired, re-run the same seed with it disabled.
     * If the *same* violation still happens, keep it disabled. The result is a run that needs only the
     * remaining fault types, which usually reads like a short story of the bug.
     */
    fun shrinkAndReport(
        seed: Long,
        config: SimConfig,
        scenario: suspend Simulation.() -> Unit,
        violation: Violation,
        testName: String?,
        runsBefore: Int,
        shrink: Boolean,
    ): FailureReport {
        var bestConfig = config
        var best = runOnce(seed, config, scenario)
        val notes = ArrayList<String>()
        if (best.failure == null || !best.failure!!.sameAs(violation)) {
            // Nondeterminism violations are found by a second run; keep the original outcome description.
            notes += "This failure did not reproduce identically on a re-run; the trace below is from the re-run."
        }
        var minimised = false
        val shrinkable = violation.kind != ViolationKind.NONDETERMINISM && violation.kind != ViolationKind.ESCAPED_DISPATCHER
        if (shrink && shrinkable && best.failure?.sameAs(violation) == true) {
            minimised = true
            val order = FaultType.builtIn + best.sim.recorder.faultsSeen.filter { it !in FaultType.builtIn }
            val candidates = order.filter { config.isEnabled(it) && (it in best.sim.recorder.faultsSeen || it == FaultType.INTERLEAVING || it == FaultType.REORDER) }
            for (fault in candidates) {
                val trial = bestConfig.copy { disabledFaults.add(fault) }
                val outcome = runOnce(seed, trial, scenario)
                if (outcome.failure?.sameAs(violation) == true) {
                    bestConfig = trial
                    best = outcome
                }
            }
        }
        val final = best.failure ?: violation
        val involved = best.sim.recorder.faultsSeen.filter { bestConfig.isEnabled(it) }.toCollection(LinkedHashSet())
        return FailureReport(
            violation = final,
            seed = seed,
            disabledFaults = bestConfig.disabledFaults,
            faultsInvolved = involved,
            minimised = minimised,
            trace = best.sim.recorder.events(),
            droppedEvents = best.sim.recorder.dropped,
            stats = best.stats,
            replayToken = ReplayToken(seed, bestConfig.disabledFaults, best.stats.fingerprint).toString(),
            testName = testName,
            runsBeforeFailure = runsBefore,
            notes = notes,
        )
    }
}

/**
 * Compact, copy-pasteable description of a run: `SL1:<seed>:<disabled faults or ->:<fingerprint>`.
 * The fingerprint lets SyncLab tell you when a replay no longer matches the recorded run.
 */
public class ReplayToken(public val seed: Long, public val disabledFaults: Set<FaultType>, public val fingerprint: Long?) {
    override fun toString(): String {
        val faults = if (disabledFaults.isEmpty()) "-" else disabledFaults.map { it.id }.sorted().joinToString("+")
        val fp = fingerprint?.let { (it ushr 32).toInt().toUInt().toString(16).padStart(8, '0') } ?: "-"
        return "SL1:$seed:$faults:$fp"
    }

    internal fun applyTo(config: SimConfig): SimConfig = config.copy { disabledFaults = this@ReplayToken.disabledFaults.toMutableSet() }

    public companion object {
        /** Parses a token produced by [toString]. Throws [IllegalArgumentException] with a hint when malformed. */
        public fun parse(token: String): ReplayToken {
            val parts = token.trim().split(':')
            require(parts.size == 4 && parts[0] == "SL1") {
                "Not a SyncLab replay token: '$token'. Expected SL1:<seed>:<faults>:<fingerprint>, copied from a failure report."
            }
            val seed = requireNotNull(parts[1].toLongOrNull()) { "Bad seed in replay token '$token'" }
            val faults = if (parts[2] == "-") emptySet() else parts[2].split('+').map(::FaultType).toSet()
            val fp = if (parts[3] == "-") null else parts[3].toUIntOrNull(16)?.toLong()
            return ReplayToken(seed, faults, fp?.let { it shl 32 })
        }
    }

    /** Only the high 32 bits are kept in the token; compare on those. */
    internal fun matches(fingerprint: Long): Boolean = this.fingerprint == null || (this.fingerprint ushr 32) == (fingerprint ushr 32)
}
