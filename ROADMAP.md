# Roadmap

The order follows what blocks adoption, not what is fun to build. Nothing here is published yet.

## 0.1: first public release (current)

- [x] Deterministic engine, virtual time, seeded same-instant interleavings
- [x] Message-level network faults, partitions, crash/restart, clock skew, custom fault types
- [x] `always` / `eventually` / `converges` / `…Equal` checks, exploration over seeds, fault shrinking, replay tokens
- [x] Determinism verification, escaped-dispatcher detection (JVM/Native)
- [x] Ktor client adapter, JUnit 5 naming, JSON + HTML reports, GitHub job summary
- [x] Four example integrations with seeded bugs; quick start compiled in CI; mutation test script
- [ ] **Before tagging:** publish to Maven Central (needs owner authorization and signing keys; see [RELEASING.md](RELEASING.md))
- [ ] Run native and browser tests in CI (macOS runner with an iOS simulator, Linux runner)
- [ ] CI matrix entry against the latest kotlinx-coroutines release (watch the `Delay` internal API)

## 0.2: adoption

- [ ] Gradle plugin: `synclab { runs = …; nightlyRuns = … }`, forwards `-Psynclab.replay` to test JVMs, aggregates reports into one HTML index
- [ ] `synclab-kotest` integration (property-test style, Kotest generators for workloads)
- [ ] Operation-level shrinking: after fault types, drop workload operations that are not needed (delta debugging on `perform` calls)
- [ ] Clock drift (clocks running at different speeds), not just offsets and jumps
- [ ] Fault schedules as data: record which messages faulted, and replay with edits ("what if message #26 had arrived?")
- [ ] Trace diff in the HTML viewer (passing vs failing seed, before vs after a fix)

## 0.3: more of the real world

- [ ] Durable-storage fault model: lose unflushed writes on crash, opt-in torn writes for an explicit `flush()` API
- [ ] Adapters: Retrofit/OkHttp `Call.Factory`, WebSockets (Ktor client sessions as one-way streams), gRPC-Kotlin
- [ ] SQLDelight in-memory driver backed by `DurableStorage`
- [ ] Linearizability and sequential-consistency checkers for recorded operation histories (a small Knossos/Elle-style checker over `perform` histories)
- [ ] Coverage-guided exploration: prefer seeds that reach new (event kind, node, fault) combinations

## Toward community adoption

1. Publish 0.1 with the quick start, examples and honest capability docs.
2. Write up reproductions of 2-3 real public sync bugs (lost-response duplicates, LWW under skew,
   cursor gaps) as runnable examples, and link them from the README.
3. Offer the Ktor adapter's approach to the Ktor team as a documented testing pattern.
4. Keep `good first issue`s ready: adapters, report-viewer improvements, more examples.
5. Measure: time-to-first-failing-test for new users (target: under 10 minutes), and issues that mention confusing reports.
6. Avoid feature creep: every new fault type needs a real bug it would have caught.
