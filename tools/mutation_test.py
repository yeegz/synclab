#!/usr/bin/env python3
"""
Mutation testing for SyncLab's core.

Applies one hand-written mutant at a time to the core sources, runs the test suites that should
notice, and reports which mutants were killed (some test failed) or survived (all tests passed).
A survivor means a real behaviour of the framework is not pinned down by any test.

Hand-written mutants instead of PIT: KMP common code compiles to several targets and PIT only
understands JVM bytecode of plain JVM projects; targeted mutants also map directly to the
guarantees we document (ordering, fault injection, isolation, shrinking, replay).

Usage: python3 tools/mutation_test.py            (from the repository root)
"""
import pathlib, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORE = ROOT / "synclab-core/src/commonMain/kotlin/dev/synclab"

MUTANTS = [
    ("scheduler ignores same-instant shuffle", "internal/SimDispatcher.kt",
     "tieBreakOverride ?: if (shuffle) r else 0L", "tieBreakOverride ?: 0L"),
    ("heap orders later tasks first", "internal/SimDispatcher.kt",
     "a.time != b.time -> a.time < b.time", "a.time != b.time -> a.time > b.time"),
    ("delay() does not wait", "internal/SimDispatcher.kt",
     "val task = schedule(timeMillis, Runnable {", "val task = schedule(0L, Runnable {"),
    ("withTimeout fires immediately", "internal/SimDispatcher.kt",
     "val task = schedule(timeMillis, block)", "val task = schedule(0L, block)"),
    ("loss decision uses the wrong roll", "Network.kt",
     "lost = lossRoll < lossRate", "lost = dupRoll < lossRate"),
    ("duplicates never injected", "Network.kt",
     "duplicate = kind != MessageKind.RESPONSE && dupRoll", "duplicate = false && dupRoll"),
    ("responses never lost", "Network.kt",
     "val lossRate = if (kind == MessageKind.RESPONSE) c.responseLossRate else c.dropRate",
     "val lossRate = if (kind == MessageKind.RESPONSE) 0.0 else c.dropRate"),
    ("partitions ignored", "Network.kt",
     "from !in isolated && to !in isolated && (from to to) !in cut", "from !in isolated && to !in isolated"),
    ("in-flight messages survive link cuts", "Network.kt",
     "                if (!isReachable(from, to)) {\n                    val reason", "                if (false) {\n                    val reason"),
    ("FIFO links not enforced", "Network.kt",
     "if (last != null && at <= last.time) {", "if (false && last != null && at <= last.time) {"),
    ("crash does not cancel node work", "Node.kt",
     "scope?.cancel(NodeCrash(name))", "scope?.let { }"),
    ("durable storage wiped on restart", "Node.kt",
     "        restarts++\n", "        restarts++\n        storage.keys.forEach { storage.remove(it) }\n"),
    ("always-invariants never fail on false", "Simulation.kt",
     "            if (!result) {", "            if (false) {"),
    ("settling does not heal the network", "Simulation.kt",
     "        network.heal()\n        nodes.values", "        nodes.values"),
    ("eventually checks treated as satisfied", "Simulation.kt",
     "var satisfied = pendingEventually().isEmpty()", "var satisfied = true"),
    ("shrinker keeps faults that are not needed", "Run.kt",
     "if (outcome.failure?.sameAs(violation) == true) {", "if (outcome.failure?.sameAs(violation) == false) {"),
    ("replay token ignores disabled faults", "Run.kt",
     "internal fun applyTo(config: SimConfig): SimConfig = config.copy { disabledFaults = this@ReplayToken.disabledFaults.toMutableSet() }",
     "internal fun applyTo(config: SimConfig): SimConfig = config"),
    ("fingerprint ignores event details", "Trace.kt",
     "append(fault?.id ?: \"-\").append('|').append(detail)", "append(fault?.id ?: \"-\")"),
    ("disabled faults still fire", "Simulation.kt",
     "internal fun faultEnabled(fault: FaultType): Boolean = config.isEnabled(fault)",
     "internal fun faultEnabled(fault: FaultType): Boolean = true"),
    ("clock skew not applied to wall clock", "Node.kt",
     "public fun nowMillis(): Long = EPOCH_START + sim.nowMillis + offsetMillis",
     "public fun nowMillis(): Long = EPOCH_START + sim.nowMillis"),
    ("determinism check always passes", "Run.kt",
     "if (second.stats.fingerprint == first.stats.fingerprint && second.failure == null) return null", "return null"),
    ("eventuallyEqual compares expected with itself", "Simulation.kt",
     "eventually += name to { expected() == actual() }", "eventually += name to { expected() == expected() }"),
]

# Tiered: the fast core suites first; the examples (which assert that bugs are *caught*) only if needed.
TIERS = [
    [":synclab-core:jvmTest", ":synclab-ktor:jvmTest"],
    [":examples:expense-ledger:test", ":examples:offline-tasks:test", ":examples:collab-text:test", ":examples:ktor-sync:test"],
]
HANG_SECONDS = 240

def gradle(tasks) -> str:
    """Returns 'pass', 'fail' or 'hang'. A hang counts as killed: CI would time out and go red."""
    cmd = [str(ROOT / "gradlew"), *tasks, "-Psynclab.native=false", "--offline", "-q"]
    env = dict(**__import__("os").environ, SYNCLAB_REPORTS="off")
    try:
        ok = subprocess.run(cmd, cwd=ROOT, capture_output=True, env=env, timeout=HANG_SECONDS).returncode == 0
        return "pass" if ok else "fail"
    except subprocess.TimeoutExpired:
        subprocess.run([str(ROOT / "gradlew"), "--stop"], cwd=ROOT, capture_output=True)
        return "hang"

def run_tests() -> str:
    for tier in TIERS:
        r = gradle(tier)
        if r != "pass":
            return r
    return "pass"

def main():
    print("Baseline run...", flush=True)
    if run_tests() != "pass":
        sys.exit("Baseline tests fail; fix them before mutation testing.")
    killed, survived = [], []
    for name, rel, old, new in MUTANTS:
        path = CORE / rel
        original = path.read_text()
        if original.count(old) != 1:
            sys.exit(f"Mutant '{name}': pattern found {original.count(old)} times in {rel}; update the script.")
        path.write_text(original.replace(old, new))
        start = time.time()
        try:
            result = run_tests()
        finally:
            path.write_text(original)
        (survived if result == "pass" else killed).append(name)
        label = {"pass": "SURVIVED", "fail": "killed  ", "hang": "killed (hung)"}[result]
        print(f"{label}  {name}  ({time.time() - start:.0f}s)", flush=True)
    print(f"\nMutation score: {len(killed)}/{len(MUTANTS)} killed")
    for s in survived:
        print(f"  survivor: {s}")
    sys.exit(1 if survived else 0)

if __name__ == "__main__":
    main()
