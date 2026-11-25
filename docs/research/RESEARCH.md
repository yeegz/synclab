# SyncLab research: prior art and adoption barriers

Date: 2026-11-25. Method: README/docs fetches and web search. Items marked **(unverified)** came from search snippets, background knowledge, or pages I could not read in full. Star/issue counts are snapshots and will drift.

## 1. Summary table

| Tool | Model | Deterministic? | Language | Assertions / checker | Setup cost for an app team |
|---|---|---|---|---|---|
| Toxiproxy | Real TCP proxy, toxics via HTTP API | No (jitter, toxicity %, real timing) | Go server, many client libs | None; you write your own | Low-medium: run a daemon, repoint clients at proxy ports |
| Jepsen | Real cluster over SSH, nemesis + generator | No | Clojure | Yes (Knossos, Elle) | Very high |
| Elle / Knossos | Offline history checkers | n/a (checks recorded histories) | Clojure (JVM usable) | Yes: transactional anomalies; Knossos = linearizability | Medium: must emit Jepsen-format histories |
| Maelstrom | Local Jepsen workbench, nodes are stdin/stdout JSON processes | No | Any node language; JVM/Clojure harness | Yes (via Elle) | Medium; toy protocols, not app code |
| FoundationDB sim | Whole cluster in one thread on Flow | Yes | C++ (Flow) | In-test workloads | Architectural; not portable |
| TigerBeetle VOPR | Tick-driven simulator, production code, stubbed clock/net/disk | Yes (seed + commit) | Zig | Protocol-aware invariants | Architectural; not portable |
| Antithesis | Deterministic hypervisor, runs unmodified containers | Yes | Any (SDKs for several) | You write properties | Commercial; contact-for-access |
| turmoil | In-process sim of hosts, net, fs on one thread | Yes (seeded) | Rust/tokio | Your asserts; no shrinking documented | Low for tokio apps |
| madsim | Drop-in tokio replacement runtime | Yes | Rust | Your asserts | Medium: swap crates, patch deps |
| Coyote | Binary-rewrites .NET async, controlled scheduling | Yes (seeded, replayable traces) | C#/.NET | Your asserts | Medium |
| kotlinx-coroutines-test | Virtual-time scheduler for coroutines | Yes, only for dispatchers sharing the scheduler | Kotlin (KMP) | Plain asserts; Turbine for flows | Low |
| Kotest / jqwik | Property-based testing + shrinking | Seeded generators | Kotlin / Java | Properties | Low |
| Flaker (KMP) | In-app slow/flaky network simulator | Not stated | Kotlin | None | Low |
| ditchoom socket-testkit (KMP) | Socket-lib test kit, "deterministic replay" claimed | Unverified | Kotlin | Unverified | Unverified |

## 2. Per-tool notes

### Toxiproxy
- A TCP proxy with toxics: latency (with jitter), bandwidth, slow_close, timeout, reset_peer, slicer, limit_data, packet_loss. "Down" is done by disabling the proxy. Toxics are deterministic by default; randomness comes from jitter, `toxicity`, and loss rate. You repoint clients at proxy ports and drive toxics through an HTTP API or client libs. Source: https://github.com/Shopify/toxiproxy
- Limits (README plus structure): TCP only, no UDP mentioned; operates at byte-stream level so it cannot express "request succeeded, response dropped" at message granularity, only closing/resetting a stream; real wall-clock time; no assertions, no seed/replay; cannot simulate clock skew or process crash; API is unauthenticated by default.
- Issue themes seen: races and lockups in the proxy itself, e.g. #667 (data race on SIGINT during init), #22 (race removing a proxy), #88 (deleting a timeout toxic can lock up proxy requests), #254 (API hangs after a long wait inside testcontainers). Index: https://github.com/Shopify/toxiproxy/issues. I only saw titles, not discussions.

### Jepsen, Elle, Knossos, Maelstrom
- Jepsen: opaque-box tests of real binaries on real clusters, with faulty networks, clock skew, partial failures, and generated concurrent histories checked against a model. https://jepsen.io/analyses
- Setup (README): Clojure tests run through Leiningen; control node needs JVM 21+, Gnuplot, Graphviz; DB nodes need SSH and root; Docker is not supported officially; containers lack real clocks so clock-skew testing needs VMs. https://github.com/jepsen-io/jepsen
- Elle: infers dependency cycles from client observations; detects Adya anomalies (G0, G1a/b/c, G-single, G2) plus duplicate writes and garbage reads; needs list-append or rw-register style workloads; EPL-2.0; Clojure but callable from the JVM; claims roughly linear scaling versus Knossos' exponential growth with concurrency; authors recommend manually checking reports. https://github.com/jepsen-io/elle
- Maelstrom: local workbench; nodes are any binary speaking JSON on stdin/stdout; nemeses are partition, kill, pause; simulated latency and message loss; checks via Elle. Needs a JVM and the release jar. It is not deterministic and tests toy protocols you write to its spec, not your existing app code. https://github.com/jepsen-io/maelstrom
- Why app teams rarely adopt: cluster/SSH/Clojure prerequisites above; it targets database vendors. This is my inference from the setup docs, not a measured adoption survey.

### Deterministic simulation (FDB, TigerBeetle, Antithesis, Fowler)
- FoundationDB: whole cluster in one single-threaded process on Flow, which runs both production and simulated; injects network/machine/datacenter faults; "swizzle-clogging" (clog a random subset of links one at a time, unclog in random order) found most effective; tens of thousands of nightly runs. https://apple.github.io/foundationdb/testing.html
- TigerBeetle VOPR: runs production code with stubbed clock, network, disk; seed plus git commit reproduces a run; faults include drop, reorder, partition, disk corruption. https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/internals/vopr.md . A 2026 post argues for protocol-aware checks per replica because black-box approaches miss invariants that never surface at the API; it also states the approach requires logical and physical determinism. https://tigerbeetle.com/blog/2026-08-20-protocol-aware-dst
- Fowler's overview: needs single-threaded core, non-blocking design, seams for Network/FileIO/Clock, and bans on system clock, unseeded randomness, and unordered-collection iteration; "can't easily be added later"; message-level simulators skip byte-framing bugs. https://martinfowler.com/articles/deterministic-simulation-testing.html
- Antithesis: deterministic hypervisor, runs unmodified containers, "multiverse" of explored timelines, you write properties; access via contacting the vendor; pricing not on the page I read. https://antithesis.com/docs/introduction/how_antithesis_works/

### turmoil, madsim, Coyote
- turmoil (tokio-rs): multiple hosts on one thread, seeded RNG, latency/drops/partitions/crashes/torn writes; README calls it experimental; crates split into turmoil, turmoil-net, turmoil-fs; ~1.3k stars. No shrinking or replay tooling documented. https://github.com/tokio-rs/turmoil
- madsim: tokio-compatible runtime; kill process, disconnect network; adoption requires swapping tokio/tonic/etc. crates and `[patch.crates-io]` overrides; used by RisingWave; ~1.2k stars. https://github.com/madsim-rs/madsim
- Coyote (.NET): rewrites assemblies to control concurrency, systematically explores interleavings, supports declared nondeterminism (timeouts, failures) and seeds/reproducible traces; explicitly "not a verification system." Archived/maintenance status not on the page I read **(unverified)**. https://microsoft.github.io/coyote/
- Go equivalents: `detsim` exists as a deterministic virtual-time scheduler with Raft example (search snippet only, unverified): https://pkg.go.dev/github.com/arshnah/detsim . Go's stdlib `testing/synctest` virtual-time bubbles **(unverified, background knowledge, not fetched)**.
- Java: Tickloom (from Fowler's article authors) is a Java DST teaching/framework; not examined **(unverified)**.

### kotlinx-coroutines-test and Turbine
- Virtual time only skips delays for dispatchers that share the test's TestCoroutineScheduler; code on `Dispatchers.IO` waits in real time. Recommended fix is injecting dispatchers. https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-test/README.md , https://developer.android.com/kotlin/coroutines/test
- Open issue #4045: `currentTime`/`TestTimeSource` stop advancing once work moves to a non-skipping dispatcher (Ktor testApplication case). Maintainer says clock behavior cannot be supported conceptually cleanly, and a `NoDelaySkipping` test-injected context element is under discussion (#3179). https://github.com/Kotlin/kotlinx.coroutines/issues/4045
- Implication: any SyncLab design must own the dispatcher/clock seam; hard-coded `Dispatchers.IO` or `System.currentTimeMillis()` in user code silently breaks virtual time. Same constraint Fowler lists.
- Turbine: pull-based awaitItem()/awaitComplete() over Flow; useful for assertions but has no notion of faults or seeds. https://github.com/cashapp/turbine
- runTest timeout behavior: I did not read a specific issue; **unverified**.

### Property-based testing
- Kotest: shrinking via per-generator Shrinkers, default bound 1,000; `Arb.bind` shrinks components, `flatMap` only the outer value, `arbitrary {}` has no automatic shrinking. Seeds page and any stateful testing support not read: **unverified**; a Kotlin Slack thread suggests no built-in stateful testing at some past date. https://kotest.io/docs/proptest/property-test-shrinking.html
- jqwik: bounded default shrinking, a Stateful Testing section (actions, invariants, rerun falsified chains) and a Kotlin module with a "Quirks and Bugs" section per the table of contents; contents unread **(unverified)**. https://jqwik.net/docs/current/user-guide.html
- Take-away: value shrinking is mature; shrinking a fault schedule (which drops/partitions, at which virtual time) is a different problem and I found no Kotlin tool that does it. turmoil/madsim docs I read do not mention it either.

### Local-first / sync stacks: how they test
- Yjs: only an anecdotal forum claim of hours of simulated fuzzing before releases (unverified, not an official statement).
- Automerge, ElectricSQL, Replicache/Reflect, Realm/Atlas, Firebase: I found no public DST or fault-injection suites for app-side sync **(unverified; absence of search results is weak evidence)**.
- PowerSync: replicates rows to clients and leaves uploads to the app's own backend endpoint, so idempotency is the developer's problem (secondary summary). TanStack DB has a PowerSync-vs-SQLite oracle test. Docs: https://docs.powersync.com/handling-writes/custom-conflict-resolution
- josephg's reference CRDT implementations plus fuzzers show CRDT-library-level fuzzing exists, but that tests merge algebra, not the app's upload/retry/outbox code: https://github.com/josephg/reference-crdts

## 3. Adoption barriers we observed

1. **Architectural prerequisite.** DST needs injectable clock/network/randomness and no stray threads or unordered iteration; Fowler states it can't easily be retrofitted. https://martinfowler.com/articles/deterministic-simulation-testing.html
2. **Runtime lock-in.** madsim requires replacing library crates and patching dependencies; turmoil only works for tokio code. https://github.com/madsim-rs/madsim
3. **Operational weight.** Jepsen needs SSH nodes, root, Clojure, Leiningen; Docker unsupported. https://github.com/jepsen-io/jepsen
4. **Virtual time leaks in Kotlin.** Dispatchers.IO / real clocks escape the scheduler. https://github.com/Kotlin/kotlinx.coroutines/issues/4045
5. **Real-network tools are non-replayable.** Toxiproxy has timing-dependent behavior and its own races/lockups. https://github.com/Shopify/toxiproxy/issues
6. **No standard checker for app-level sync.** Elle needs specific workload shapes and Jepsen-format histories; app teams have business invariants, not serializability. https://github.com/jepsen-io/elle
7. **Sync bugs are real but hand-reproduced.** One developer reproduced a cursor-skipping bug by manually freezing a server mid-write and syncing another device in the gap; this is exactly a schedule a simulator would enumerate. https://hackernoon.com/how-a-cursor-race-silently-broke-my-offline-first-app
8. **Idempotency/retry bugs recur in practice write-ups:** retry loops on permanent failures, duplicates after crash mid-send, concurrent duplicate creates, unreliable dedup keys. https://dev.to/salazarismo/the-hidden-problems-of-offline-first-sync-idempotency-retry-storms-and-dead-letters-1no8 . Also lost-response duplicates (server commits, response lost): https://oneuptime.com/blog/post/2026-07-29-prevent-duplicate-writes-after-client-timeout/view (snippet only, not fetched).
9. **Rocicorp tracker note:** offline-write recovery (mutation recovery) needed re-porting from Replicache to Zero, showing outbox durability is a recurring feature-level risk: https://bugs.rocicorp.dev/p/zero/issue/246605 (snippet only).
10. **CRDT teams' stated pains are scale/memory, not testing.** Cinapse cited Automerge WASM 4GB limit and LWW handling when moving to PowerSync. https://www.powersync.com/blog/why-cinapse-moved-away-from-crdts-for-sync . Treat "clock-based LWW bugs" as a hypothesis; I did not find a specific public issue thread to cite.

I did not find a survey quantifying developer complaints; the above are individual write-ups and docs.

## 4. Is there already a Kotlin/KMP deterministic sync simulator?

Searched Maven Central listings (via mvnrepository snippets), klibs.io, GitHub. Findings:
- **ditchoom/socket-testkit**: described as a transport-neutral test and deterministic-replay core for KMP with a fault vocabulary for TCP/UDP/QUIC. The repo (Apache-2.0, ~14 stars, active commits) lists RFC_DETERMINISTIC_SIMULATION.md. Purpose appears to be testing the socket library itself, not app sync logic. Details **unverified**; mvnrepository returned 403 and README did not show the contents. https://github.com/ditchoom/socket
- **Flaker** (RotBolt): slow/flaky network simulator for mobile dev; no seeds or replay documented. https://github.com/RotBolt/Flaker/blob/main/README.md
- **SimKt** (way-zer): coroutine discrete-event simulator, general purpose, not a sync harness (snippet only). https://github.com/way-zer/SimKt
- Nothing found that combines seeded virtual time, message-level faults (including lost-response), crash/restart with durable-state semantics, clock skew, convergence/invariant assertions, seed replay, and fault-set shrinking, for Kotlin app code. "Nothing found" is not proof of absence; private or obscure repos may exist.

## 5. Underserved use case and recommended narrow v0.1 scope

**Underserved use case.** App developers whose mobile/desktop client has a hand-written outbox/upload/pull loop (or wraps PowerSync/Firebase-style row sync) and who currently test it with mocks plus manual airplane-mode checks. Their bugs (duplicate writes on lost response, lost updates, cursor gaps, retry-forever, bad LWW with skewed clocks) are schedule-dependent. Existing options are either real-network (Toxiproxy), cluster-scale (Jepsen), or in other languages (turmoil/madsim/Coyote). No identified Kotlin tool covers it.

**Recommended v0.1 (my recommendation, not a finding):**
1. **Scope: client-server request/response sync only.** One simulated server, N simulated clients, message-level network (not byte-level, following TigerBeetle/Fowler tradeoff). Defer peer-to-peer/CRDT merge testing.
2. **Own the seams explicitly.** Ship `SimClock` + `SimDispatcher` + `SimTransport` (a Ktor `HttpClientEngine` adapter first, since Ktor is the KMP default), and a lint/doc rule: no `Dispatchers.IO`, no `Clock.System` in sync code. Build on the shared `TestCoroutineScheduler` rather than a new runtime, and fail loudly when real time is observed.
3. **Fault set, in priority order:** drop, duplicate, delay/reorder, partition/disconnect, **response lost after server applied** (the signature case), crash/restart of a client with a user-supplied durable-state snapshot rule, per-node clock skew. Skip disk faults and byte-level faults in v0.1.
4. **Assertions:** convergence-after-quiescence ("heal then drain, all replicas equal") plus user-registered invariants evaluated at every step; a small built-in set (no duplicate server-side effects per client mutation id, no acknowledged write lost).
5. **Replay and shrinking:** record the run as a fault schedule (list of timestamped fault events), derive it from the seed, and shrink the schedule (delta-debug: remove events, shorten partitions, reduce node/op counts), not just input values. Print `seed + schedule` to paste into a regression test. This is the clear differentiator versus what I found.
6. **Out of scope for v0.1:** real sockets, Kotlin/Native-specific scheduling, UI testing, hypervisor-level determinism, CRDT-specific checkers, Elle-style history analysis (export histories for Elle later).
7. **Validate with 2-3 seeded real bugs** (lost-response duplicate create, cursor gap like the Hackernoon case, LWW with skew) reproduced from public write-ups before announcing.

**Biggest risks:** users' code leaking real time/threads (mitigate with detection); effort to adapt real engines (Ktor, SQLDelight) to the simulator; the market may be small if teams are happy with mocks, which I could not quantify.
