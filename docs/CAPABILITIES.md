# What SyncLab can and cannot find

SyncLab finds bugs that show up when **your code runs inside the simulation** and meets the
**failures SyncLab models**. This page spells out both halves precisely, so you can decide what else
you still need (integration tests, chaos in staging, Jepsen-style testing for your database).

> Passing thousands of seeds is strong evidence for the scenarios you wrote. It is not a proof that
> your distributed system is correct.

## Simulated inside SyncLab (deterministic, replayable)

These are modelled at the level of **messages between nodes** and **events on a single virtual clock**:

| Class | Mechanism | Typical bugs it exposes |
|---|---|---|
| Message loss | `DROP`: a request or one-way message is never delivered | Missing retries, optimistic UI that never reconciles |
| Lost response after success | `RESPONSE_LOSS`: handler ran, reply dropped | Double writes, double charges, duplicated rows on retry |
| Duplicate delivery | `DUPLICATE`: receiver executes the request twice | Non-idempotent handlers, counters, "add" operations |
| Fast failure | `REQUEST_FAILURE`: rejected before processing | Retry logic that treats every failure as "maybe applied" or the reverse |
| Reordering and delay | `REORDER`, `DELAY`: per-message latency, spikes; FIFO when off | Ops applied out of order, stale-write-wins, cursor gaps |
| Disconnects and partitions | `PARTITION`: node isolated or groups cut, including messages already in flight | Offline queues, interrupted batches, split-brain on clients |
| Crash and restart | `CRASH`: node's coroutines cancelled, memory dropped, app rebuilt from `DurableStorage` | Outbox lost on restart, "cleared before acknowledged", resumable sync cursors |
| Interrupted synchronization | Combination of the above at arbitrary points of a multi-step sync | Partially applied batches, receipts written separately from data |
| Clock differences | `CLOCK_SKEW`: per-node offset, jumps | Last-writer-wins by wall clock, expiry/TTL logic, ordering by device time |
| Same-instant interleavings | `INTERLEAVING`: seeded order of tasks due at the same virtual instant | Check-then-act races between coroutines, double confirmation from two devices |
| Custom message faults | `network.intercept(FaultType("…"))`: drop, delay, duplicate or replace payloads you choose | Corrupted or truncated payloads, version skew, poison messages |
| Nondeterminism in your code | Re-runs sampled seeds and compares trace fingerprints | Unseeded `Random`, real clocks, shared state between tests, Ktor's default retry jitter |
| Work escaping the simulation (JVM) | Detects coroutines resumed from foreign threads | `Dispatchers.IO`/`Default` inside sync code, platform callbacks |
| Liveness at the end | `eventually`/`converges` after healing; idle detection | Sync that never retries, clients that never resync after reconnect, deadlocks |

Checks:

- `always(name) { … }` / `alwaysEqual`: evaluated after **every scheduler step**, so a transient
  violation is caught at the exact event that caused it.
- `eventually`, `eventuallyEqual`, `converges`: evaluated after the scenario ends, the network heals,
  crashed nodes restart and probabilistic faults stop, up to `settleTimeout`.
- Any exception thrown by the scenario or by node code, and handler exceptions (configurable).

## Not simulated (outside SyncLab's model)

| Not covered | Why | What to use instead |
|---|---|---|
| Real TCP/TLS/HTTP stacks, proxies, DNS, MTU, keep-alive, HTTP/2 streams | No real sockets. The Ktor adapter replaces the engine | Integration tests against a real server; Toxiproxy for byte-level faults |
| Byte-level corruption, partial reads and writes, slow streams | Messages are whole objects | Toxiproxy (`slicer`, `limit_data`), fuzzing parsers |
| OS-thread races and memory visibility | One thread runs everything | `kotlinx-lincheck`, stress tests, thread sanitizers |
| Real disk behaviour: fsync, torn writes, full disks | `DurableStorage` is a perfect map that survives crashes | Database-specific crash tests; SQLite's own test suite |
| Your database's isolation guarantees | The server logic runs in memory as you write it | Jepsen/Elle, or tests against the real DB |
| Mobile OS behaviour: process death timing, background limits, push delivery | Not modelled | Device tests; `CRASH` covers the in-app consequences only |
| Work on other threads or real timers | Escapes virtual time | Inject dispatchers. SyncLab reports escapes on the JVM, but cannot simulate that work |
| Bugs outside your scenario | SyncLab explores the workloads and fault mixes you describe | More scenarios, more seeds (`SYNCLAB_RUNS`), nightly runs |
| Byzantine or malicious nodes | Nodes run your code faithfully | Security review, signed payloads |
| Performance under real load | Virtual time makes everything "fast" | Load tests |

## Important caveats

- **Minimal fault sets are per seed.** "Reproduces with only RESPONSE_LOSS" means that for this seed,
  turning off any other enabled fault type made the failure disappear or change. It is a strong hint,
  not a causal proof. Turning a fault type off also shifts timing.
- **Same-instant ordering, not arbitrary preemption.** Coroutines interleave only at suspension
  points, as in a single-threaded dispatcher. SyncLab explores which of the tasks *due at the same
  instant* runs first, plus all timing that comes from latency. It does not preempt in the middle of
  non-suspending code.
- **Exploration is random, not exhaustive.** Seeds sample the space. Rare interleavings may need
  many seeds. Run more in CI (`SYNCLAB_RUNS`) and use `Hostile` conditions to raise the odds.
- **Determinism is checked by sampling.** `explore` re-runs the first seed and every 10th seed.
  Nondeterminism that only appears on other seeds is caught when you replay a failure: the replay
  token's fingerprint will not match.
- **Each node's wall clock is virtual time plus an offset.** Drift (clocks running at different
  speeds) is not modelled yet; jumps are.
- **Handlers are the unit of atomicity.** A server handler that does not suspend runs atomically,
  which matches a single-threaded server or a transaction. If your real server is not atomic there,
  model that with `yield()`/`delay()` at the points where it can interleave.

## How to tell what a run actually exercised

`explore` returns `faultCoverage`: how many runs saw each fault type. Assert on it, so a test cannot
pass because the fault you care about never fired:

```kotlin
val result = explore(runs = 200, config = SimConfig { network = NetworkConditions.Hostile }) { … }
check((result.faultCoverage[FaultType.RESPONSE_LOSS] ?: 0) > 20)
```
