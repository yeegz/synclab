package dev.synclab

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Small helpers for generating workloads from a seeded Random. They deliberately stay tiny:
// SyncLab's "generator" is the seed; shrinking happens on fault types, not on values.

/** A random element of [items]. */
public fun <T> Random.pick(items: List<T>): T {
    require(items.isNotEmpty()) { "Cannot pick from an empty list" }
    return items[nextInt(items.size)]
}

/** A random element of [items]. */
public fun <T> Random.pick(vararg items: T): T = pick(items.toList())

/** True with probability [p]. */
public fun Random.chance(p: Double): Boolean = nextDouble() < p

/** A random duration within [range], at millisecond resolution. */
public fun Random.duration(range: ClosedRange<Duration>): Duration {
    val lo = range.start.inWholeMilliseconds
    val hi = range.endInclusive.inWholeMilliseconds
    return (if (hi <= lo) lo else nextLong(lo, hi + 1)).milliseconds
}

/** Picks one of [options] with probability proportional to its weight and runs it. */
public suspend fun <R> Random.weighted(vararg options: Pair<Int, suspend () -> R>): R {
    val total = options.sumOf { it.first }
    require(total > 0 && options.all { it.first >= 0 }) { "Weights must be non-negative and not all zero" }
    var roll = nextInt(total)
    for ((w, action) in options) {
        if (roll < w) return action()
        roll -= w
    }
    error("unreachable")
}

/** A deterministic, UUID-shaped id from this random stream. Use instead of `UUID.randomUUID()`. */
public fun Random.nextId(prefix: String = ""): String {
    val hex = "0123456789abcdef"
    return prefix + buildString(16) { repeat(16) { append(hex[nextInt(16)]) } }
}
