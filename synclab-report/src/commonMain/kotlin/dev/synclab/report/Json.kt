package dev.synclab.report

import dev.synclab.FailureReport
import dev.synclab.RunSummary
import dev.synclab.TraceEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stable, versioned JSON shape for CI tooling. Additive changes only within a schema version. */
@Serializable
public data class RunSummaryJson(
    val schema: Int = 1,
    val test: String?,
    val passed: Boolean,
    val runs: Int,
    val firstSeed: Long,
    val totalSteps: Long,
    val totalVirtualMillis: Long,
    val wallMillis: Long,
    val failure: FailureJson? = null,
)

@Serializable
public data class FailureJson(
    val kind: String,
    val name: String,
    val message: String,
    val atMillis: Long,
    val node: String?,
    val seed: Long,
    val replayToken: String,
    val faultsInvolved: List<String>,
    val minimised: Boolean,
    val disabledFaults: List<String>,
    val events: Long,
    val droppedEvents: Long,
    val steps: Long,
    val messages: Long,
    val virtualMillis: Long,
    val fingerprint: String,
    val trace: List<EventJson>,
)

@Serializable
public data class EventJson(
    val i: Long,
    val t: Long,
    val kind: String,
    val node: String? = null,
    val peer: String? = null,
    val msg: Long? = null,
    val fault: String? = null,
    val detail: String,
)

public object SyncLabJson {
    private val json = Json { prettyPrint = false; encodeDefaults = true; explicitNulls = false }
    private val pretty = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }

    public fun toJson(summary: RunSummary): RunSummaryJson = RunSummaryJson(
        test = summary.testName,
        passed = summary.passed,
        runs = summary.runs,
        firstSeed = summary.firstSeed,
        totalSteps = summary.totalSteps,
        totalVirtualMillis = summary.totalVirtualMillis,
        wallMillis = summary.wallMillis,
        failure = summary.failure?.let(::toJson),
    )

    public fun toJson(r: FailureReport): FailureJson = FailureJson(
        kind = r.violation.kind.name,
        name = r.violation.name,
        message = r.violation.message,
        atMillis = r.violation.atMillis,
        node = r.violation.node,
        seed = r.seed,
        replayToken = r.replayToken,
        faultsInvolved = r.faultsInvolved.map { it.id },
        minimised = r.minimised,
        disabledFaults = r.disabledFaults.map { it.id }.sorted(),
        events = r.stats.events,
        droppedEvents = r.droppedEvents,
        steps = r.stats.steps,
        messages = r.stats.messages,
        virtualMillis = r.stats.virtualMillis,
        fingerprint = r.stats.fingerprint.toULong().toString(16),
        trace = r.trace.map(::toJson),
    )

    public fun toJson(e: TraceEvent): EventJson = EventJson(e.index, e.timeMillis, e.kind.name, e.node, e.peer, e.messageId, e.fault?.id, e.detail)

    /** One line, for JSON Lines files. */
    public fun encodeLine(summary: RunSummary): String = json.encodeToString(RunSummaryJson.serializer(), toJson(summary))

    public fun encodePretty(summary: RunSummary): String = pretty.encodeToString(RunSummaryJson.serializer(), toJson(summary))

    public fun encode(failure: FailureReport): String = json.encodeToString(FailureJson.serializer(), toJson(failure))

    public fun decode(text: String): RunSummaryJson = json.decodeFromString(RunSummaryJson.serializer(), text)
}
