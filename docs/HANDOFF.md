# Hand-off

Status as of 2026-11-25. Read this first if you are picking SyncLab up (human or agent).

## Done

- **Core engine** (`synclab-core`): own virtual-time dispatcher, seeded interleavings, simulated
  network with nine built-in fault types plus custom interceptors, crash/restart with durable storage,
  clock skew, chaos, invariants (`always`, `alwaysEqual`, `eventually`, `eventuallyEqual`, `converges`),
  `simulate` / `explore`, fault shrinking, replay tokens, sampled determinism checks, escaped-dispatcher detection.
- **Integrations**: `synclab-ktor` (real Ktor client over the simulated network), `synclab-report`
  (JSON Lines, HTML viewer, GitHub summary, zero-config), `synclab-junit5` (exact names).
- **Examples**: expense ledger, offline tasks (clock skew), collaborative text (CRDT), Ktor inventory.
  Each one has tests that the correct version passes and that seeded bugs are caught.
- **Verification**: core tests on JVM, JS and macOS native; examples; quick start compiled as a test;
  mutation script (`tools/mutation_test.py`); benchmarks (`docs/BENCHMARKS.md`).
- **Docs**: README, QUICKSTART, CAPABILITIES, ADOPTING, EXTENDING, ARCHITECTURE, SPEC, CI,
  COMPATIBILITY, VERSIONING, DESIGN, BENCHMARKS, ROADMAP, CONTRIBUTING, RELEASING, CHANGELOG, research.

## Not done / needs the owner

- **Not published anywhere.** No GitHub remote, no Maven Central. Both need explicit approval (see RELEASING.md).
- iOS simulator and Linux native **tests** have not run (only compiled); CI is set up to run iOS on a macOS runner.
- `api/*.api` dumps exist for binary-compatibility checks; regenerate them with `./gradlew apiDump` after intended API changes.
- Roadmap items (Gradle plugin, operation shrinking, clock drift, storage faults) are not started.

## Decisions (and why)

- **Own dispatcher instead of `TestCoroutineScheduler`**: needed for per-step invariants, seeded
  same-instant ordering, exact idleness and foreign-thread detection. Risk: relies on the `Delay`
  internal interface (documented in COMPATIBILITY.md).
- **Shrink fault types, not values**: cheap (about 10 re-runs), and it maps directly to fixes.
- **Fixed seeds by default**: CI is reproducible; nightly uses `SYNCLAB_FIRST_SEED=random`.
- **Default network is Flaky**: a first test should find real bugs; `Perfect` is one line away.
- **Group `io.github.yeegz.synclab`, package `dev.synclab`**: verifiable on Central without a domain;
  change `GROUP` in gradle.properties if the owner prefers another namespace.

## Sources

`docs/research/RESEARCH.md` (prior art and adoption barriers, with links). The Bupples codebase was
read (read-only, not modified) for real-world ledger invariants. The example is an independent implementation.

## Resume

```bash
cd ~/Documents/synclab
./gradlew check -Psynclab.native=false     # fast loop (JVM + JS)
./gradlew check                            # everything, including native
python3 tools/mutation_test.py             # guarantees (takes 10-20 min)
./gradlew :benchmarks:run -Psynclab.native=false
```
