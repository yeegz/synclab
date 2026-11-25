# Architecture

SyncLab is a small deterministic discrete-event simulator built on Kotlin coroutines. Everything a
scenario does, including your app's coroutines, timers, network messages and faults, becomes a task
on **one queue ordered by virtual time**. One thread drains that queue.

```
 test ──► explore(runs) ──► Runner.runOnce(seed) ──► Simulation.execute(scenario)
                │                                         │
                │                        ┌────────────────┴───────────────┐
                │                        │ SimDispatcher (virtual time)    │◄── delay / withTimeout / launch / yield
                │                        │  heap of tasks (time, tie, seq) │
                │                        └────────────────┬───────────────┘
                │                                         │ runNext() → checkAlways()
                │              ┌──────────────┬───────────┴────────┬───────────────┐
                │           SimNode(s)     SimNetwork          Chaos agent      Recorder
                │        app factory,     rpc / send,         random            trace ring buffer,
                │        scope, clock,    fate per message,   disconnects       rolling fingerprint,
                │        DurableStorage   partitions,         and crashes       faults seen
                │                         interceptors
                │
                ├── on failure ──► shrink (disable fault types one by one, same seed) ──► FailureReport
                └── listeners ──► synclab-report (JSON Lines, HTML), JUnit names (synclab-junit5)
```

## Modules

| Module | Depends on | Purpose |
|---|---|---|
| `synclab-core` (KMP) | `kotlinx-coroutines-core` only | Engine, network, nodes, invariants, exploration, shrinking, replay |
| `synclab-ktor` (KMP) | core, `ktor-client-core`, `ktor-client-mock` | `HttpClientEngine` over the simulated network, and a tiny HTTP router for simulated servers |
| `synclab-report` (KMP, file writer on JVM) | core, `kotlinx-serialization-json` | JSON schema for CI, self-contained HTML viewer, `ServiceLoader` auto-registration, GitHub job summary |
| `synclab-junit5` (JVM) | core, `junit-jupiter-api` | `@SyncLabTest` / `SyncLabExtension` for exact test names in reports and replay commands |

The core has one runtime dependency, so adding it to an app's test classpath is cheap.

## Key design decisions

**Own the dispatcher, not `TestCoroutineScheduler`.** `SimDispatcher` implements `CoroutineDispatcher`
and `Delay` (an `@InternalCoroutinesApi` interface, which `kotlinx-coroutines-test` also implements).
Owning it lets SyncLab:

1. break ties between tasks due at the same instant with a seeded random value (`INTERLEAVING`);
2. check `always` invariants after every task;
3. detect idleness exactly (needed for "stuck" and "went idle without converging" reports);
4. detect resumption from foreign threads (`Dispatchers.IO` escapes);
5. run synchronously from any test function on every platform, including JS where `runBlocking` does not exist.

The trade-off is reliance on an internal coroutines interface. It has been stable for years and
is pinned by tests (`SchedulerTest`); see [COMPATIBILITY.md](COMPATIBILITY.md).

**The seed decides everything, through separate random streams.** Each purpose (scheduler,
network, scenario, chaos, interceptors, each node, each clock) gets its own `Random`, derived from
the seed with a SplitMix64 mix of the purpose name. Each message draws **all** of its random
decisions up front, whether or not a fault is enabled. So when the shrinker turns a fault type off,
the other decisions in the run stay the same, and the shrunk trace remains recognisably the same story.

**Faults are categories, so shrinking is cheap and meaningful.** Rather than shrinking generated
values (Kotest/jqwik style), SyncLab shrinks the *set of fault types*: at most about 10 re-runs of
one seed. The result maps directly onto how developers think about fixes ("this needs idempotency
because of `RESPONSE_LOSS`").

**Nodes are factories.** `node(name) { … }` stores the factory and runs it again on restart, against
the same `DurableStorage`. That is how SyncLab models a process restart without your app knowing
about SyncLab.

**Messages are objects, not bytes.** That keeps the model at the level where sync bugs live
(requests, responses, retries) and makes traces readable. The Ktor adapter converts to and from
real `HttpRequestData` so your client stack still runs.

**Traces are bounded, fingerprints are complete.** The recorder keeps the last `traceLimit` events
for reports but hashes every event (FNV-1a) into a rolling fingerprint. Determinism checks and
replay verification compare fingerprints, then locate the first divergence using per-event hashes.

## Run lifecycle

1. **Scenario phase.** The scenario body runs as a coroutine, with chaos (if configured) in the background.
   After each task: foreign-thread check, budgets, `always` invariants.
2. **Settle phase.** The network heals, probabilistic faults stop (latency remains), crashed nodes restart,
   then the run continues until all `eventually` checks hold, the system goes idle, or `settleTimeout` passes.
3. **Cleanup.** The root job is cancelled and cancellation handlers are drained. Nothing outlives the run.
4. **Verification.** `simulate` re-runs the seed to compare fingerprints; `explore` does this on sampled seeds.
5. **Shrink and report** on failure, then notify listeners.

## Package map

```
dev.synclab
  Run.kt            simulate / explore / Runner (determinism check, shrinking) / ReplayToken
  Simulation.kt     Simulation (scenario receiver), invariants, run loop, settle, chaos
  Network.kt        SimNetwork: fates, transmit, RPC, one-way messages, partitions, interceptors
  Node.kt           SimNode, NodeScope, SimClock, DurableStorage, RpcClient
  Config.kt         SimConfig, NetworkConditions (Perfect/Flaky/Hostile), Chaos
  Faults.kt         FaultType
  Trace.kt          TraceEvent, EventKind, Recorder (ring buffer + fingerprint)
  Report.kt         Violation, FailureReport (text rendering), RunSummary, SimulationListener, SyncLab
  Gen.kt            pick / chance / duration / weighted / nextId helpers
  internal/         SimDispatcher + TaskHeap, platform expect/actuals
```
