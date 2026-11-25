# SyncLab v0.1 specification

## Problem

App teams building offline-first, local-first or client-server sync ship bugs that only appear
under specific failure timing: a reply lost after the server applied a write, a retry with a new
id, two devices disagreeing about the time, a crash between "sent" and "marked sent". Existing
tools either inject faults non-deterministically at the byte level with no assertions (Toxiproxy),
or require clusters and a different language (Jepsen). Mature deterministic simulators are tied
to one runtime (FoundationDB/Flow, TigerBeetle/Zig, turmoil/madsim/tokio, Coyote/.NET). We found no
Kotlin library that combines seeded virtual time, message-level faults including lost responses,
crash/restart, clock skew, business invariants, replay and fault shrinking.
Evidence: [research/RESEARCH.md](research/RESEARCH.md).

## Users

Kotlin/KMP developers who own sync logic: mobile and desktop clients with outboxes, the server
endpoints they call, and shared domain code. They already write unit tests and use coroutines.
They will not run a cluster or learn Clojure, and they will not restructure their app.

## Narrow v0.1 scope

In scope:

1. Deterministic, seeded runs of multiple simulated nodes on one virtual clock (JVM, JS, Native).
2. Message-level network: request/response RPC and one-way messages, with drop, duplicate,
   response loss, fast failure, latency, spikes, reordering, disconnects and partitions.
3. Node crash and restart with explicit durable storage; per-node clock skew and jumps.
4. Same-instant interleaving exploration.
5. `always` invariants after every step; `eventually`/`converges` after healing.
6. Property-style exploration over seeds with workload generation from the seeded `Random`.
7. Fault-type shrinking, replay tokens, trace fingerprints, determinism verification.
8. Integrations: Ktor client engine, JUnit 5 naming, kotlin.test (via `AssertionError`).
9. Reports: readable failure text, JSON Lines for CI, self-contained HTML viewer.

Out of scope for v0.1 (see [ROADMAP.md](../ROADMAP.md)): byte-level faults, real sockets, disk fault
models, clock drift, value-level shrinking of workloads, exhaustive or model-checked exploration,
a Gradle plugin, an IDE plugin, Android instrumentation integration.

## Functional requirements

| ID | Requirement | Verified by |
|---|---|---|
| F1 | Same seed + same code ⇒ identical trace fingerprint | `RunTest.sameSeedSameTrace`, cross-thread isolation test |
| F2 | `delay`, `withTimeout`, `yield`, `launch` run on virtual time, instantly | `SchedulerTest` |
| F3 | Tasks due at the same instant run in seeded order; FIFO when `INTERLEAVING` is off | `SchedulerTest` |
| F4 | Each built-in fault behaves as documented (processed vs not processed, fast vs timeout) | `NetworkTest` |
| F5 | Messages in flight across a newly cut link are dropped | `NetworkTest.partitionCutsMessagesAlreadyInFlight` |
| F6 | Crash cancels node work, drops memory, keeps durable storage; restart re-runs factory | `NodeTest` |
| F7 | `always` fails at the exact step; `eventually` after healing; `converges` explains differences | `RunTest` |
| F8 | Stuck scenarios, endless loops and busy loops end with a clear report, never a hang | `SchedulerTest` |
| F9 | Nondeterministic scenarios are reported with the first differing event | `RunTest`, `InventorySyncTest` (Ktor jitter) |
| F10 | Work resumed from foreign threads is reported (JVM) | `JvmSpecificTest` |
| F11 | Shrinking yields a minimal fault-type set for the seed; custom fault types participate | `RunTest`, `NetworkTest` |
| F12 | A replay token reproduces the same failure and fingerprint | `RunTest.replayTokenReproducesTheSameFailure` |
| F13 | Reports are written with zero configuration when `synclab-report` is on the classpath | `ReportTest` |
| F14 | A real Ktor client (plugins included) runs on virtual time over the simulated network | `SimHttpTest`, `InventorySyncTest` |

## Non-functional requirements

- One runtime dependency in core (`kotlinx-coroutines-core`).
- A meaningful first test in under five minutes ([QUICKSTART.md](QUICKSTART.md), compiled in CI).
- Typical sync scenario: thousands of runs per second on a laptop ([BENCHMARKS.md](BENCHMARKS.md)).
- Explicit API mode; binary compatibility tracked with the Kotlin binary-compatibility validator.
- Failure output fits in a terminal and tells a story: violation, minimal faults, injected faults, last events, replay command.
