package dev.synclab

/** Why a run failed. */
public enum class ViolationKind {
    /** An `always` invariant was false (or threw) after some step. */
    INVARIANT,
    /** `eventually` / `converges` checks did not hold after the system was healed and given time to settle. */
    NOT_CONVERGED,
    /** The scenario body threw (e.g. a failed assertion). */
    ASSERTION,
    /** Code under test threw an uncaught exception in a node or background coroutine. */
    UNCAUGHT,
    /** The scenario never finished: deadlock, or waiting on something outside the simulation. */
    STUCK,
    /** Virtual-time, step or wall-clock budget exceeded. */
    BUDGET,
    /** Running the same seed twice produced different traces. */
    NONDETERMINISM,
    /** Work escaped the simulation dispatcher (e.g. `Dispatchers.IO`). */
    ESCAPED_DISPATCHER,
}

/** A single problem found during a run. */
public class Violation(
    public val kind: ViolationKind,
    /** Invariant name, or a short title. */
    public val name: String,
    public val message: String,
    public val atMillis: Long,
    public val node: String? = null,
    public val cause: Throwable? = null,
) {
    /** Two violations are "the same bug" for shrinking when kind and name match. */
    internal fun sameAs(other: Violation): Boolean = kind == other.kind && name == other.name
}

/** Counters for one run. */
public class RunStats(
    public val seed: Long,
    public val virtualMillis: Long,
    public val steps: Long,
    public val events: Long,
    public val messages: Long,
    public val wallMillis: Long,
    public val fingerprint: Long,
)

/** Everything needed to understand and reproduce a failure. */
public class FailureReport(
    public val violation: Violation,
    public val seed: Long,
    public val disabledFaults: Set<FaultType>,
    /** Fault types that were active and observed in the (minimised) failing run. */
    public val faultsInvolved: Set<FaultType>,
    /** True when the shrinker confirmed each of [faultsInvolved] is required. */
    public val minimised: Boolean,
    public val trace: List<TraceEvent>,
    public val droppedEvents: Long,
    public val stats: RunStats,
    public val replayToken: String,
    public val testName: String?,
    /** For `explore`: how many seeds passed before this one. */
    public val runsBeforeFailure: Int,
    public val notes: List<String> = emptyList(),
) {
    /** Human-readable report used as the exception message. */
    public fun render(lastEvents: Int = 25): String = buildString {
        val v = violation
        appendLine()
        appendLine("SyncLab found a problem (seed $seed, at t=${formatMillis(v.atMillis)} of virtual time).")
        appendLine()
        appendLine("  ${kindTitle(v.kind)}: ${v.name}" + (v.node?.let { "  [node $it]" } ?: ""))
        v.message.lines().forEach { appendLine("    $it") }
        appendLine()
        if (faultsInvolved.isEmpty()) {
            appendLine("  Faults involved: none. This fails on a fault-free network too.")
        } else {
            val ids = faultsInvolved.joinToString(", ") { it.id }
            appendLine(if (minimised) "  Reproduces with only these fault types: $ids  (minimal for this seed: turning any one off hides it)" else "  Faults active in this run: $ids")
        }
        if (runsBeforeFailure > 0) appendLine("  ($runsBeforeFailure earlier seeds passed.)")
        notes.forEach { appendLine("  Note: $it") }
        appendLine()
        val injected = trace.filter { it.fault != null && it.kind != EventKind.SUPPRESSED }
        if (injected.isNotEmpty()) {
            appendLine("  Injected faults (${injected.size}${if (injected.size > 12) ", first 12" else ""}):")
            injected.take(12).forEach { appendLine("    ${it.formatLine()}") }
            appendLine()
        }
        val shown = trace.takeLast(lastEvents)
        val hidden = stats.events - shown.size
        appendLine("  Last ${shown.size} of ${stats.events} events" + if (hidden > 0) " (full trace in the HTML/JSON report if synclab-report is installed):" else ":")
        shown.forEach { appendLine("    ${it.formatLine()}") }
        appendLine()
        appendLine("  Reproduce exactly:")
        appendLine("    SYNCLAB_REPLAY=$replayToken ./gradlew test" + (testName?.let { " --tests \"${it.substringBeforeLast('.')}\"" } ?: ""))
        appendLine("  or pin it in the test while you debug:")
        appendLine("    simulate(replay = \"$replayToken\") { ... }")
        append("  ${stats.steps} steps, ${stats.messages} messages, ${formatMillis(stats.virtualMillis)} virtual, ${stats.wallMillis} ms real.")
    }

    private fun kindTitle(k: ViolationKind) = when (k) {
        ViolationKind.INVARIANT -> "Invariant broken"
        ViolationKind.NOT_CONVERGED -> "Did not converge"
        ViolationKind.ASSERTION -> "Assertion failed"
        ViolationKind.UNCAUGHT -> "Uncaught exception"
        ViolationKind.STUCK -> "Scenario stuck"
        ViolationKind.BUDGET -> "Budget exceeded"
        ViolationKind.NONDETERMINISM -> "Nondeterministic code under test"
        ViolationKind.ESCAPED_DISPATCHER -> "Work escaped the simulation"
    }
}

/** Summary of a passing (or failing) simulate/explore call, given to [SimulationListener]s. */
public class RunSummary(
    public val testName: String?,
    public val runs: Int,
    public val passed: Boolean,
    public val totalSteps: Long,
    public val totalVirtualMillis: Long,
    public val wallMillis: Long,
    public val firstSeed: Long,
    public val failure: FailureReport?,
)

/**
 * Observes simulation results, e.g. to write reports. On the JVM, implementations listed in
 * `META-INF/services/dev.synclab.SimulationListener` are discovered automatically
 * (that is how `synclab-report` works with zero configuration).
 */
public interface SimulationListener {
    public fun onFinished(summary: RunSummary)
}

/** Global SyncLab hooks. */
public object SyncLab {
    private var manual: List<SimulationListener> = emptyList()
    private val discovered: List<SimulationListener> by lazy { discoverListenersSafely() }

    /** Registers a listener for every subsequent run in this process. */
    public fun addListener(listener: SimulationListener) {
        manual = manual + listener
    }

    public fun removeListener(listener: SimulationListener) {
        manual = manual - listener
    }

    internal fun notify(summary: RunSummary) {
        for (l in discovered + manual) {
            try {
                l.onFinished(summary)
            } catch (_: Throwable) {
                // A broken reporter must never change a test's outcome.
            }
        }
    }

    private fun discoverListenersSafely(): List<SimulationListener> =
        try {
            dev.synclab.internal.discoverListeners()
        } catch (_: Throwable) {
            emptyList()
        }
}
