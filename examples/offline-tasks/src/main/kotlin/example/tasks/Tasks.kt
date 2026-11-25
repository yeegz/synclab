package example.tasks

// An offline-first task list. Each task is a last-writer-wins register; the interesting part is
// how "last" is decided. Plain application code with no SyncLab dependency.

public data class TaskValue(val title: String, val done: Boolean)

/**
 * One edit of one task.
 * @property stamp ordering key chosen by the conflict policy (device wall clock, or a hybrid logical clock).
 * @property basedOn the edit the user was looking at when they made this one (null for a new task).
 */
public data class Edit(
    val id: String,
    val taskId: String,
    val value: TaskValue,
    val stamp: Long,
    val device: String,
    val basedOn: String?,
)

public enum class ConflictPolicy {
    /** Newest device wall-clock time wins. Simple, common, and wrong when clocks disagree. */
    LWW_DEVICE_CLOCK,

    /** Hybrid logical clock: max(wall clock, highest stamp seen + 1). Never orders an edit before one it was based on. */
    HYBRID_LOGICAL_CLOCK,
}

/** Total order used by both server and clients: stamp, then device as a tie-breaker. */
public fun Edit.newerThan(other: Edit): Boolean = stamp > other.stamp || (stamp == other.stamp && device > other.device)

public interface TasksApi {
    public suspend fun push(edits: List<Edit>): Set<String>
    public suspend fun snapshot(): Map<String, Edit>
}

public class TasksServer {
    public val current: MutableMap<String, Edit> = linkedMapOf()

    /** Applies edits (idempotent: re-applying an edit never changes anything). Returns acknowledged ids. */
    public fun push(edits: List<Edit>): Set<String> {
        for (e in edits) {
            val existing = current[e.taskId]
            if (existing == null || e.newerThan(existing)) current[e.taskId] = e
        }
        return edits.map { it.id }.toSet()
    }

    public fun snapshot(): Map<String, Edit> = current.toMap()
}

public class TasksClient(
    public val device: String,
    private val api: TasksApi,
    private val wallClock: () -> Long,
    private val newId: () -> String,
    private val policy: ConflictPolicy,
    private val disk: MutableMap<String, Any?>,
) {
    @Suppress("UNCHECKED_CAST")
    private val outbox = disk.getOrPut("outbox") { mutableListOf<Edit>() } as MutableList<Edit>

    @Suppress("UNCHECKED_CAST")
    private val local = disk.getOrPut("local") { linkedMapOf<String, Edit>() } as MutableMap<String, Edit>

    private var highestSeen: Long = disk["highestSeen"] as Long? ?: 0L

    /** What the user sees. */
    public val tasks: Map<String, Edit> get() = local.toMap()
    public val pending: List<Edit> get() = outbox.toList()

    public fun edit(taskId: String, value: TaskValue): Edit {
        val now = wallClock()
        val stamp = when (policy) {
            ConflictPolicy.LWW_DEVICE_CLOCK -> now
            ConflictPolicy.HYBRID_LOGICAL_CLOCK -> maxOf(now, highestSeen + 1)
        }
        observe(stamp)
        val e = Edit(newId(), taskId, value, stamp, device, basedOn = local[taskId]?.id)
        local[taskId] = e
        outbox += e
        return e
    }

    public suspend fun sync() {
        if (outbox.isNotEmpty()) {
            val acked = api.push(outbox.toList())
            outbox.removeAll { it.id in acked }
        }
        val server = api.snapshot()
        for ((taskId, e) in server) {
            observe(e.stamp)
            val mine = local[taskId]
            // Keep an unsynced local edit if it is still newer than what the server has.
            if (mine == null || outbox.none { it.id == mine.id } || e.newerThan(mine)) local[taskId] = e
        }
    }

    private fun observe(stamp: Long) {
        if (stamp > highestSeen) {
            highestSeen = stamp
            disk["highestSeen"] = stamp // must survive restarts, or the clock can go backwards
        }
    }
}
