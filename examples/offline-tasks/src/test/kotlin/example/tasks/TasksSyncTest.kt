package example.tasks

import dev.synclab.FaultType
import dev.synclab.NetworkConditions
import dev.synclab.SimConfig
import dev.synclab.Simulation
import dev.synclab.SimulationFailure
import dev.synclab.chance
import dev.synclab.duration
import dev.synclab.explore
import dev.synclab.junit5.SyncLabTest
import dev.synclab.nextId
import dev.synclab.pick
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private suspend fun Simulation.tasksScenario(policy: ConflictPolicy) {
    val server = node("server", crashable = false) {
        TasksServer().also { s ->
            serve<List<Edit>, Set<String>>("push") { _, edits -> s.push(edits) }
            serve<Unit, Map<String, Edit>>("snapshot") { _, _ -> s.snapshot() }
        }
    }
    val devices = listOf("phone", "tablet", "laptop").map { name ->
        node(name) {
            val push = rpc<List<Edit>, Set<String>>("server", "push")
            val snap = rpc<Unit, Map<String, Edit>>("server", "snapshot")
            val api = object : TasksApi {
                override suspend fun push(edits: List<Edit>) = push(edits)
                override suspend fun snapshot() = snap(Unit)
            }
            TasksClient(name, api, wallClock = clock::nowMillis, newId = { random.nextId() }, policy = policy,
                disk = storage.getOrPut("disk") { mutableMapOf<String, Any?>() }).also { c ->
                every(1.seconds) { runCatching { c.sync() } }
            }
        }
    }

    // Every edit made, so we can reason about causality afterwards.
    val history = LinkedHashMap<String, Edit>()

    converges("every device shows what the server has") {
        listOf("server" to server.app.current.toMap()) + devices.map { it.name to it.app.tasks }
    }
    eventually("an edit made on top of another edit is never overwritten by it") {
        // If edit B was made while looking at edit A, A must not be the final value.
        val overwritten = history.values.mapNotNull { it.basedOn }.toSet()
        server.app.current.values.none { it.id in overwritten }.also { ok ->
            if (!ok) {
                val lost = server.app.current.values.first { it.id in overwritten }
                val newer = history.values.filter { it.basedOn == lost.id }
                error("Task ${lost.taskId} shows '${lost.value.title}' from ${lost.device} (stamp ${lost.stamp}), " +
                    "but ${newer.joinToString { "${it.device} later changed it to '${it.value.title}' (stamp ${it.stamp})" }}.")
            }
        }
    }

    repeat(random.nextInt(10, 30)) { i ->
        sleep(random.duration(100.milliseconds..2.seconds))
        val d = random.pick(devices)
        if (!d.isUp) return@repeat
        d.perform("edit") {
            val known = tasks.keys.toList()
            val taskId = if (known.isEmpty() || random.chance(0.2)) "task-${random.nextInt(1000)}" else random.pick(known)
            val old = tasks[taskId]?.value
            val e = edit(taskId, TaskValue(title = "v$i by $device", done = old?.done?.not() ?: false))
            history[e.id] = e
        }
    }
}

private val skewed = SimConfig {
    network = NetworkConditions.Flaky
    clockSkew = 10.seconds
    chaos { disconnects(every = 3.seconds..10.seconds, lasting = 1.seconds..15.seconds) }
}

class TasksSyncTest {
    @SyncLabTest
    fun `wall-clock last-writer-wins loses edits when device clocks disagree`() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 200, config = skewed) { tasksScenario(ConflictPolicy.LWW_DEVICE_CLOCK) }
        }
        assertEquals("an edit made on top of another edit is never overwritten by it", failure.report.violation.name, failure.message)
        assertTrue(FaultType.CLOCK_SKEW in failure.report.faultsInvolved, "clock skew should be required:\n${failure.message}")
    }

    @SyncLabTest
    fun `the same workload with perfect clocks hides the bug`() {
        explore(runs = 200, config = skewed.copy { disable(FaultType.CLOCK_SKEW) }) { tasksScenario(ConflictPolicy.LWW_DEVICE_CLOCK) }
    }

    @SyncLabTest
    fun `hybrid logical clocks preserve causality even with skewed clocks`() {
        val result = explore(runs = 300, config = skewed) { tasksScenario(ConflictPolicy.HYBRID_LOGICAL_CLOCK) }
        assertTrue((result.faultCoverage[FaultType.CLOCK_SKEW] ?: 0) > 250)
    }
}
