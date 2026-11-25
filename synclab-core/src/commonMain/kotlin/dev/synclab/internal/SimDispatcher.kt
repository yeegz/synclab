package dev.synclab.internal

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Single-threaded, virtual-time coroutine dispatcher.
 *
 * Every coroutine in a simulation runs here. Tasks are ordered by (virtual time, tie-break, sequence).
 * When [shuffle] is on, tasks due at the same instant get a seeded random tie-break, which explores
 * different interleavings of "simultaneous" events while staying fully reproducible for a given seed.
 *
 * It implements [Delay] so that `delay`, `withTimeout` and friends use virtual time.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class SimDispatcher(
    private val random: Random,
    var shuffle: Boolean,
) : CoroutineDispatcher(), Delay {

    internal class Task(
        val time: Long,
        val tieBreak: Long,
        val seq: Long,
        val block: Runnable,
    ) {
        var cancelled: Boolean = false
    }

    /** Current virtual time in milliseconds since the simulation started. */
    var now: Long = 0L
        private set

    /** Number of tasks executed so far. */
    var executed: Long = 0L
        private set

    private val heap = TaskHeap()
    private var seq = 0L
    private val ownerThread: Any? = captureThread()

    /** Set when code resumes onto this dispatcher from a foreign thread (e.g. `Dispatchers.IO`). */
    @kotlin.concurrent.Volatile
    var foreignDispatch: String? = null
        private set

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!checkThread()) return
        schedule(0L, block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        if (!checkThread()) return
        val task = schedule(timeMillis, Runnable {
            with(continuation) { this@SimDispatcher.resumeUndispatched(Unit) }
        })
        continuation.invokeOnCancellation { task.cancelled = true }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        if (!checkThread()) return DisposableHandle { }
        val task = schedule(timeMillis, block)
        return DisposableHandle { task.cancelled = true }
    }

    fun schedule(delayMillis: Long, block: Runnable, tieBreakOverride: Long? = null): Task {
        // Always consume exactly one random value per task so that toggling [shuffle] or faults
        // does not shift every later random decision.
        val r = random.nextLong()
        val time = when {
            delayMillis <= 0L -> now
            now > Long.MAX_VALUE - delayMillis -> Long.MAX_VALUE
            else -> now + delayMillis
        }
        val task = Task(time, tieBreakOverride ?: if (shuffle) r else 0L, seq++, block)
        heap.add(task)
        return task
    }

    /** Virtual time of the next runnable task, or null when nothing is scheduled. */
    fun nextTime(): Long? {
        while (true) {
            val top = heap.peek() ?: return null
            if (top.cancelled) heap.poll() else return top.time
        }
    }

    /** Runs the next task. Returns false when the queue is empty. */
    fun runNext(): Boolean {
        while (true) {
            val task = heap.poll() ?: return false
            if (task.cancelled) continue
            if (task.time > now) now = task.time
            executed++
            task.block.run()
            return true
        }
    }

    val pendingTasks: Int get() = heap.size

    private fun checkThread(): Boolean {
        if (ownerThread == null || isSameThread(ownerThread)) return true
        if (foreignDispatch == null) foreignDispatch = currentThreadName()
        return false
    }
}

/** Binary min-heap ordered by (time, tieBreak, seq). Common code has no PriorityQueue. */
internal class TaskHeap {
    private var items = arrayOfNulls<SimDispatcher.Task>(64)
    var size = 0
        private set

    fun add(task: SimDispatcher.Task) {
        if (size == items.size) items = items.copyOf(size * 2)
        var i = size++
        items[i] = task
        while (i > 0) {
            val parent = (i - 1) / 2
            if (less(items[i]!!, items[parent]!!)) {
                swap(i, parent); i = parent
            } else break
        }
    }

    fun peek(): SimDispatcher.Task? = if (size == 0) null else items[0]

    fun poll(): SimDispatcher.Task? {
        if (size == 0) return null
        val top = items[0]
        size--
        items[0] = items[size]
        items[size] = null
        var i = 0
        while (true) {
            val l = 2 * i + 1
            val r = l + 1
            var smallest = i
            if (l < size && less(items[l]!!, items[smallest]!!)) smallest = l
            if (r < size && less(items[r]!!, items[smallest]!!)) smallest = r
            if (smallest == i) break
            swap(i, smallest); i = smallest
        }
        return top
    }

    private fun less(a: SimDispatcher.Task, b: SimDispatcher.Task): Boolean = when {
        a.time != b.time -> a.time < b.time
        a.tieBreak != b.tieBreak -> a.tieBreak < b.tieBreak
        else -> a.seq < b.seq
    }

    private fun swap(i: Int, j: Int) {
        val t = items[i]; items[i] = items[j]; items[j] = t
    }
}
