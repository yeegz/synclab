package example.ledger

// A small shared-expense ledger with offline clients. This is ordinary application code: it knows
// nothing about SyncLab. It talks to the server through the LedgerApi interface, which a real app
// would implement with HTTP and the test implements with SyncLab's simulated network.

/** Money is always integer minor units (cents). Never Double. */
public typealias Minor = Long

public data class Expense(
    val id: String,
    val payer: String,
    val amount: Minor,
    /** Member -> share. Shares always add up to [amount]. */
    val shares: Map<String, Minor>,
)

public enum class PaymentStatus { PENDING, CONFIRMED }

/** "I paid you back": recorded by the payer, confirmed by the payee. */
public data class Payment(val id: String, val from: String, val to: String, val amount: Minor, val status: PaymentStatus)

public sealed class Op {
    public abstract val opId: String

    public data class AddExpense(override val opId: String, val expense: Expense) : Op()
    public data class RecordPayment(override val opId: String, val payment: Payment) : Op()
    public data class ConfirmPayment(override val opId: String, val paymentId: String, val by: String) : Op()
}

public sealed class OpResult {
    public object Applied : OpResult() { override fun toString(): String = "Applied" }
    /** Already applied earlier (a retry). The client treats this as success. */
    public object AlreadyApplied : OpResult() { override fun toString(): String = "AlreadyApplied" }
    public data class Rejected(val reason: String) : OpResult()
}

public sealed class Change {
    public data class ExpenseAdded(val expense: Expense) : Change()
    public data class PaymentRecorded(val payment: Payment) : Change()
    public data class PaymentConfirmed(val paymentId: String) : Change()
}

public data class PushRequest(val ops: List<Op>)
public data class PushResponse(val results: Map<String, OpResult>)
public data class PullResponse(val changes: List<Pair<Long, Change>>, val cursor: Long)

/** The client's view of the server. Real apps: Retrofit/Ktor client. Tests: SyncLab RPC. */
public interface LedgerApi {
    public suspend fun push(request: PushRequest): PushResponse
    public suspend fun pull(since: Long): PullResponse
}

/** Splits [amount] equally using largest remainder; leftover cents go to members in sorted order. */
public fun splitEqually(amount: Minor, members: Collection<String>): Map<String, Minor> {
    require(members.isNotEmpty() && amount > 0)
    val sorted = members.sorted()
    val base = amount / sorted.size
    var leftover = amount - base * sorted.size
    return sorted.associateWith { base + if (leftover-- > 0) 1 else 0 }
}

/** The ledger maths shared by server and clients. */
public class LedgerState {
    public val expenses: MutableMap<String, Expense> = linkedMapOf()
    public val payments: MutableMap<String, Payment> = linkedMapOf()

    /** Positive: the group owes this member. Negative: this member owes the group. */
    public fun balances(members: Collection<String>): Map<String, Minor> {
        val b = members.associateWith { 0L }.toMutableMap()
        for (e in expenses.values) {
            b[e.payer] = (b[e.payer] ?: 0) + e.amount
            for ((m, share) in e.shares) b[m] = (b[m] ?: 0) - share
        }
        for (p in payments.values) if (p.status == PaymentStatus.CONFIRMED) {
            b[p.from] = (b[p.from] ?: 0) + p.amount
            b[p.to] = (b[p.to] ?: 0) - p.amount
        }
        return b
    }

    /** What [from] still owes [to] directly (never negative), after confirmed payments. */
    public fun directDebt(from: String, to: String): Minor {
        var owed = 0L
        for (e in expenses.values) {
            if (e.payer == to) owed += e.shares[from] ?: 0
            if (e.payer == from) owed -= e.shares[to] ?: 0
        }
        for (p in payments.values) if (p.status == PaymentStatus.CONFIRMED) {
            if (p.from == from && p.to == to) owed -= p.amount
            if (p.from == to && p.to == from) owed += p.amount
        }
        return owed.coerceAtLeast(0)
    }

    public fun apply(change: Change) {
        when (change) {
            is Change.ExpenseAdded -> expenses[change.expense.id] = change.expense
            is Change.PaymentRecorded -> payments.putIfAbsent(change.payment.id, change.payment)
            is Change.PaymentConfirmed -> payments[change.paymentId]?.let { payments[it.id] = it.copy(status = PaymentStatus.CONFIRMED) }
        }
    }

    public fun snapshot(): Pair<Map<String, Expense>, Map<String, Payment>> = expenses.toMap() to payments.toMap()
}

/** Bugs you can switch on to watch SyncLab catch them. All off = the correct implementation. */
public data class LedgerBugs(
    /** Server applies an op again when a retry or duplicate arrives. */
    val nonIdempotentServer: Boolean = false,
    /** Client mints a fresh op id on each retry instead of keeping the one it stored. */
    val newIdOnRetry: Boolean = false,
    /** Server confirms a payment without checking it is still pending and still owed. */
    val confirmWithoutRecheck: Boolean = false,
)

public class LedgerServer(public val members: Set<String>, private val bugs: LedgerBugs = LedgerBugs()) {
    public val state: LedgerState = LedgerState()
    private val log = ArrayList<Change>()
    /** Idempotency receipts: op id -> result, written together with the change. */
    private val receipts = HashMap<String, OpResult>()

    /** Denormalised "total settled per pair", maintained incrementally like a real cache/counter column. */
    public val settledTotals: MutableMap<Pair<String, String>, Minor> = HashMap()

    public fun push(request: PushRequest): PushResponse =
        PushResponse(request.ops.associate { it.opId to applyOp(it) })

    public fun pull(since: Long): PullResponse {
        val from = since.toInt().coerceIn(0, log.size)
        return PullResponse(log.subList(from, log.size).mapIndexed { i, c -> (from + i + 1).toLong() to c }, log.size.toLong())
    }

    private fun applyOp(op: Op): OpResult {
        if (!bugs.nonIdempotentServer) receipts[op.opId]?.let { return if (it == OpResult.Applied) OpResult.AlreadyApplied else it }
        val result = when (op) {
            is Op.AddExpense -> {
                val e = op.expense
                when {
                    e.payer !in members || !members.containsAll(e.shares.keys) -> OpResult.Rejected("unknown member")
                    e.shares.values.sum() != e.amount -> OpResult.Rejected("shares do not add up")
                    else -> {
                        // With the non-idempotent bug, a retried op lands as a second expense.
                        val stored = if (bugs.nonIdempotentServer && e.id in state.expenses) e.copy(id = e.id + "-dup" + log.size) else e
                        commit(Change.ExpenseAdded(stored)); OpResult.Applied
                    }
                }
            }
            is Op.RecordPayment -> {
                val p = op.payment
                if (p.id in state.payments) OpResult.AlreadyApplied else {
                    commit(Change.PaymentRecorded(p.copy(status = PaymentStatus.PENDING))); OpResult.Applied
                }
            }
            is Op.ConfirmPayment -> {
                val p = state.payments[op.paymentId]
                when {
                    p == null -> OpResult.Rejected("no such payment")
                    op.by != p.to -> OpResult.Rejected("only the payee can confirm")
                    !bugs.confirmWithoutRecheck && p.status != PaymentStatus.PENDING -> OpResult.AlreadyApplied
                    !bugs.confirmWithoutRecheck && p.amount > state.directDebt(p.from, p.to) -> OpResult.Rejected("stale: more than is owed")
                    else -> {
                        commit(Change.PaymentConfirmed(p.id))
                        val key = p.from to p.to
                        settledTotals[key] = (settledTotals[key] ?: 0) + p.amount
                        OpResult.Applied
                    }
                }
            }
        }
        // Receipts are stored in the same "transaction" as the change. Only successes are remembered,
        // so a retry after a rejection is evaluated again.
        if (result == OpResult.Applied) receipts[op.opId] = result
        return result
    }

    private fun commit(change: Change) {
        log += change
        state.apply(change)
    }
}

/**
 * An offline-first client. Ops go to a durable outbox first and are pushed in the background;
 * the confirmed view is rebuilt from the server's change log.
 */
public class LedgerClient(
    public val member: String,
    private val api: LedgerApi,
    /** Survives app restarts. A real app would use SQLite/DataStore. */
    private val disk: MutableMap<String, Any?>,
    private val newId: () -> String,
    private val bugs: LedgerBugs = LedgerBugs(),
) {
    @Suppress("UNCHECKED_CAST")
    private val outbox: MutableList<Op> = disk.getOrPut("outbox") { mutableListOf<Op>() } as MutableList<Op>

    /** Server-confirmed state, rebuilt after a restart by pulling from cursor 0. */
    public val confirmed: LedgerState = LedgerState()
    private var cursor = 0L

    public val pendingOps: List<Op> get() = outbox.toList()

    public fun addExpense(amount: Minor, participants: Collection<String>): Op.AddExpense {
        val id = newId()
        val op = Op.AddExpense("op-$id", Expense("e-$id", member, amount, splitEqually(amount, participants)))
        outbox += op
        return op
    }

    public fun recordPayment(to: String, amount: Minor): Op.RecordPayment {
        val id = newId()
        val op = Op.RecordPayment("op-$id", Payment("p-$id", member, to, amount, PaymentStatus.PENDING))
        outbox += op
        return op
    }

    public fun confirmPayment(paymentId: String): Op.ConfirmPayment {
        val op = Op.ConfirmPayment("op-${newId()}", paymentId, member)
        outbox += op
        return op
    }

    /** One sync round: push the outbox, then pull new changes. Throws on network errors; call again later. */
    public suspend fun sync() {
        if (outbox.isNotEmpty()) {
            val batch = if (bugs.newIdOnRetry) outbox.map { it.withFreshId() } else outbox.toList()
            val response = api.push(PushRequest(batch))
            // Remove everything the server has definitively handled (applied, duplicate or rejected).
            val handled = batch.indices.filter { response.results.containsKey(batch[it].opId) }.map { outbox[it] }.toSet()
            outbox.removeAll(handled)
        }
        val pulled = api.pull(cursor)
        pulled.changes.forEach { (_, c) -> confirmed.apply(c) }
        cursor = pulled.cursor
    }

    private fun Op.withFreshId(): Op = when (this) {
        is Op.AddExpense -> copy(opId = "op-${newId()}", expense = expense.copy(id = "e-${newId()}"))
        is Op.RecordPayment -> copy(opId = "op-${newId()}")
        is Op.ConfirmPayment -> copy(opId = "op-${newId()}")
    }
}
