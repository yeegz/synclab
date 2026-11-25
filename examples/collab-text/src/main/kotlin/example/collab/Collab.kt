package example.collab

// Collaborative plain-text editing. Two document implementations share one sync layer:
//  - NaiveDoc sends "insert 'x' at index 3": simple, and diverges under concurrency.
//  - RgaDoc is a small replicated growable array (a sequence CRDT): converges regardless of order.

public data class CharId(val counter: Long, val site: String) : Comparable<CharId> {
    override fun compareTo(other: CharId): Int = compareValuesBy(this, other, { it.counter }, { it.site })
}

public sealed class EditOp {
    public abstract val opId: String

    public data class NaiveInsert(override val opId: String, val index: Int, val ch: Char) : EditOp()
    public data class NaiveDelete(override val opId: String, val index: Int) : EditOp()
    public data class RgaInsert(override val opId: String, val after: CharId?, val id: CharId, val ch: Char) : EditOp()
    public data class RgaDelete(override val opId: String, val target: CharId) : EditOp()
}

public interface Doc {
    public val text: String
    public fun localInsert(index: Int, ch: Char, opId: String): EditOp
    public fun localDelete(index: Int, opId: String): EditOp?
    /** Applies a remote op. Must be idempotent. */
    public fun applyRemote(op: EditOp)
}

public class NaiveDoc : Doc {
    private val sb = StringBuilder()
    private val seen = HashSet<String>()
    override val text: String get() = sb.toString()

    override fun localInsert(index: Int, ch: Char, opId: String): EditOp {
        val i = index.coerceIn(0, sb.length)
        sb.insert(i, ch); seen += opId
        return EditOp.NaiveInsert(opId, i, ch)
    }

    override fun localDelete(index: Int, opId: String): EditOp? {
        if (sb.isEmpty()) return null
        val i = index.coerceIn(0, sb.length - 1)
        sb.deleteAt(i); seen += opId
        return EditOp.NaiveDelete(opId, i)
    }

    override fun applyRemote(op: EditOp) {
        if (!seen.add(op.opId)) return
        when (op) {
            is EditOp.NaiveInsert -> sb.insert(op.index.coerceIn(0, sb.length), op.ch)
            is EditOp.NaiveDelete -> if (sb.isNotEmpty()) sb.deleteAt(op.index.coerceIn(0, sb.length - 1))
            else -> error("unexpected op $op")
        }
    }
}

public class RgaDoc(private val site: String) : Doc {
    private class Elem(val id: CharId, val ch: Char, var deleted: Boolean = false)

    private val elems = ArrayList<Elem>()
    private val index = HashMap<CharId, Elem>()
    private val seen = HashSet<String>()
    /** Ops waiting for the character they refer to (it may arrive later). */
    private val waiting = ArrayList<EditOp>()
    private var counter = 0L

    override val text: String get() = buildString { elems.forEach { if (!it.deleted) append(it.ch) } }

    private fun visible(): List<Elem> = elems.filter { !it.deleted }

    override fun localInsert(index: Int, ch: Char, opId: String): EditOp {
        val vis = visible()
        val i = index.coerceIn(0, vis.size)
        val op = EditOp.RgaInsert(opId, if (i == 0) null else vis[i - 1].id, CharId(++counter, site), ch)
        applyRemote(op)
        return op
    }

    override fun localDelete(index: Int, opId: String): EditOp? {
        val vis = visible()
        if (vis.isEmpty()) return null
        val op = EditOp.RgaDelete(opId, vis[index.coerceIn(0, vis.size - 1)].id)
        applyRemote(op)
        return op
    }

    override fun applyRemote(op: EditOp) {
        if (op.opId in seen) return
        if (!tryApply(op)) {
            if (waiting.none { it.opId == op.opId }) waiting += op
            return
        }
        // Applying one op may unblock others.
        var progress = true
        while (progress) {
            progress = false
            val it = waiting.iterator()
            while (it.hasNext()) {
                val w = it.next()
                if (w.opId in seen) { it.remove(); continue }
                if (tryApply(w)) { it.remove(); progress = true }
            }
        }
    }

    private fun tryApply(op: EditOp): Boolean {
        when (op) {
            is EditOp.RgaInsert -> {
                if (op.after != null && op.after !in index) return false
                if (op.id !in index) {
                    var i = if (op.after == null) 0 else elems.indexOf(index.getValue(op.after)) + 1
                    // Concurrent inserts at the same place: the larger id goes first, on every replica.
                    while (i < elems.size && elems[i].id > op.id) i++
                    val e = Elem(op.id, op.ch)
                    elems.add(i, e); index[op.id] = e
                    if (op.id.counter > counter) counter = op.id.counter
                }
            }
            is EditOp.RgaDelete -> {
                val target = index[op.target] ?: return false
                target.deleted = true
            }
            else -> error("unexpected op $op")
        }
        seen += op.opId
        return true
    }
}

/** The relay server: an append-only, idempotent op log, broadcast to everyone else. */
public class RelayServer {
    public val log: MutableList<EditOp> = ArrayList()
    private val ids = HashSet<String>()

    /** Returns true when the op is new (and should be broadcast). */
    public fun submit(op: EditOp): Boolean {
        if (!ids.add(op.opId)) return false
        log += op
        return true
    }

    public fun since(n: Int): Pair<List<EditOp>, Int> = log.drop(n) to log.size
}
