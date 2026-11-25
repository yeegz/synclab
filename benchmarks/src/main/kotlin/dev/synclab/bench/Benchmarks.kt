package dev.synclab.bench

import dev.synclab.NetworkConditions
import dev.synclab.SimConfig
import dev.synclab.SimulatedNetworkException
import dev.synclab.Simulation
import dev.synclab.SimulationFailure
import dev.synclab.explore
import dev.synclab.simulate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.milliseconds

// Plain wall-clock benchmarks with warm-up and median-of-N. Not JMH: good enough to track order-of-magnitude
// costs and regressions, not for micro-optimisation. Run with: ./gradlew :benchmarks:run

private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

private fun bench(name: String, unit: String, work: Double, warmups: Int = 3, reps: Int = 7, block: () -> Unit): Pair<String, String> {
    repeat(warmups) { block() }
    val times = (1..reps).map { measureNanoTime(block) / 1e6 }
    val ms = median(times)
    val rate = work / (ms / 1000.0)
    val line = "| $name | ${"%.1f".format(ms)} ms | ${"%,.0f".format(rate)} $unit/s |"
    println(line)
    return name to line
}

private suspend fun Simulation.counter(ops: Int, clients: Int) {
    var total = 0
    val seen = HashSet<String>()
    node("server") { serve<String, Unit> { _, id -> if (seen.add(id)) total++ } }
    val cs = (1..clients).map { i -> node("c$i") { rpc<String, Unit>("server") } }
    eventually("all ops applied") { total == ops * clients }
    parallel(*cs.map { c ->
        suspend {
            repeat(ops) { i ->
                c.perform("op") {
                    while (true) try { invoke("${c.name}-$i"); break } catch (_: SimulatedNetworkException) { delay(50) }
                }
            }
        }
    }.toTypedArray())
}

fun main() {
    println("SyncLab benchmarks — JVM ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cores, ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    println()
    println("| Benchmark | Median time | Throughput |")
    println("|---|---|---|")

    val tasks = 100_000
    bench("Scheduler: $tasks coroutines x 1 delay (SyncLab)", "tasks", tasks.toDouble()) {
        simulate(config = SimConfig { network = NetworkConditions.Perfect; verifyDeterminism = false }) {
            kotlinx.coroutines.coroutineScope { repeat(tasks) { i -> launch { delay((i % 1000).toLong()) } } }
        }
    }
    bench("Scheduler: $tasks coroutines x 1 delay (kotlinx-coroutines-test runTest)", "tasks", tasks.toDouble()) {
        runTest { repeat(tasks) { i -> launch { delay((i % 1000).toLong()) } } }
    }

    val rpcs = 10_000
    bench("RPC round trips, perfect network ($rpcs calls)", "calls", rpcs.toDouble()) {
        simulate(config = SimConfig { network = NetworkConditions.Perfect; verifyDeterminism = false }) {
            node("s") { serve<Int, Int> { _, x -> x } }
            val c = node("c") { rpc<Int, Int>("s") }
            c.perform("loop") { repeat(rpcs) { invoke(it) } }
        }
    }
    bench("RPC with retries, hostile network ($rpcs ops)", "ops", rpcs.toDouble()) {
        simulate(config = SimConfig { network = NetworkConditions.Hostile; verifyDeterminism = false }) { counter(rpcs, 1) }
    }

    bench("explore: 100 runs, 3 clients x 20 ops, flaky", "runs", 100.0, warmups = 2, reps = 5) {
        explore(runs = 100, config = SimConfig { verifyDeterminism = false }) { counter(20, 3) }
    }
    bench("explore: same, with determinism sampling (every 10th seed)", "runs", 100.0, warmups = 2, reps = 5) {
        explore(runs = 100) { counter(20, 3) }
    }
    bench("explore: 20 runs, 10 clients x 50 ops, hostile + chaos", "runs", 20.0, warmups = 1, reps = 3) {
        explore(runs = 20, config = SimConfig {
            network = NetworkConditions.Hostile; verifyDeterminism = false
            chaos { disconnects(every = 1000.milliseconds..3000.milliseconds) }
        }) { counter(50, 10) }
    }
    bench("Find + shrink a duplicate-delivery bug (non-idempotent server)", "failures", 1.0, warmups = 1, reps = 5) {
        try {
            explore(runs = 100) {
                var total = 0
                node("server") { serve<String, Unit> { _, _ -> total++ } }
                val c = node("c") { rpc<String, Unit>("server") }
                eventually("exactly 10") { total == 10 }
                repeat(10) { i -> c.perform("op") { while (true) try { invoke("$i"); break } catch (_: SimulatedNetworkException) { delay(50) } } }
            }
            error("expected a failure")
        } catch (_: SimulationFailure) {
        }
    }
    val probe = explore(runs = 100, config = SimConfig { verifyDeterminism = false }) { counter(20, 3) }
    println()
    println("Sanity: explore 100 runs x (3 clients x 20 ops) executed ${probe.totalSteps} scheduler steps, " +
        "${probe.totalVirtualMillis / 1000} s virtual; faults exercised: ${probe.faultCoverage}")
    val rt = Runtime.getRuntime()
    System.gc()
    println()
    println("Heap in use after benchmarks: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB")
}
