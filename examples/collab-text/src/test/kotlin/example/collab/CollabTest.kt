package example.collab

import dev.synclab.FaultType
import dev.synclab.NetworkConditions
import dev.synclab.SimConfig
import dev.synclab.Simulation
import dev.synclab.SimulationFailure
import dev.synclab.ViolationKind
import dev.synclab.chance
import dev.synclab.duration
import dev.synclab.explore
import dev.synclab.junit5.SyncLabTest
import dev.synclab.nextId
import dev.synclab.pick
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A device holding a document plus its sync state (outbox + log cursor). */
private class Editor(val doc: Doc) {
    val outbox = ArrayList<EditOp>()
    var cursor = 0
}

private suspend fun Simulation.collabScenario(newDoc: (site: String) -> Doc) {
    val names = listOf("ana", "ben", "chen")
    node("relay", crashable = false) {
        val server = RelayServer()
        serve<EditOp, Unit>("submit") { from, op ->
            // New ops are pushed to everyone else right away (best effort, may be lost).
            if (server.submit(op)) names.filter { it != from }.forEach { send(it, op) }
        }
        serve<Int, Pair<List<EditOp>, Int>>("since") { _, n -> server.since(n) }
        server
    }
    val editors = names.map { name ->
        node(name) {
            val e = Editor(newDoc(name))
            val submit = rpc<EditOp, Unit>("relay", "submit")
            val since = rpc<Int, Pair<List<EditOp>, Int>>("relay", "since")
            onMessage<EditOp> { _, op -> e.doc.applyRemote(op) }
            // Flush the outbox in order; a failed send is retried on the next tick.
            every(200.milliseconds) {
                while (e.outbox.isNotEmpty()) {
                    try { submit(e.outbox.first()); e.outbox.removeAt(0) } catch (_: Exception) { break }
                }
            }
            // Anti-entropy: catch up on anything the push missed.
            every(2.seconds) {
                runCatching { since(e.cursor) }.onSuccess { (ops, n) -> ops.forEach(e.doc::applyRemote); e.cursor = n }
            }
            e
        }
    }

    converges("everyone sees the same text") { editors.map { it.name to it.app.doc.text } }

    repeat(random.nextInt(15, 40)) {
        sleep(random.duration(10.milliseconds..400.milliseconds))
        val ed = random.pick(editors)
        ed.perform("type") {
            val len = doc.text.length
            val op = if (len > 0 && random.chance(0.25)) doc.localDelete(random.nextInt(len), random.nextId("op-"))
            else doc.localInsert(random.nextInt(len + 1), 'a' + random.nextInt(26), random.nextId("op-"))
            if (op != null) outbox += op
        }
    }
}

private val busy = SimConfig { network = NetworkConditions.Flaky }

class CollabTest {
    @SyncLabTest
    fun `index-based edits diverge under concurrency`() {
        val failure = assertFailsWith<SimulationFailure> {
            explore(runs = 100, config = busy) { collabScenario { NaiveDoc() } }
        }
        assertEquals(ViolationKind.NOT_CONVERGED, failure.report.violation.kind, failure.message)
    }

    @SyncLabTest
    fun `the sequence CRDT converges under loss, duplication and reordering`() {
        explore(runs = 300, config = busy.copy { network { reorder = true; duplicateRate = 0.1; dropRate = 0.1 } }) {
            collabScenario { site -> RgaDoc(site) }
        }
    }

    @SyncLabTest
    fun `the CRDT also survives partitions`() {
        explore(runs = 100, config = busy.copy { chaos { disconnects(every = 1.seconds..4.seconds, lasting = 500.milliseconds..6.seconds) } }) {
            collabScenario { site -> RgaDoc(site) }
        }.also { assertEquals(true, (it.faultCoverage[FaultType.PARTITION] ?: 0) > 50) }
    }
}
