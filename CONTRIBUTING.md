# Contributing to SyncLab

Thanks for helping. SyncLab is small on purpose: a deterministic core, a few adapters, and honest docs.

## Get going

```bash
git clone https://github.com/yeegz/synclab && cd synclab
./gradlew check -Psynclab.native=false      # JVM + JS, about a minute after the first download
./gradlew check                              # adds Kotlin/Native targets (downloads the toolchain once)
```

Requirements: JDK 17. Everything else is downloaded by Gradle.

## Layout

| Path | What |
|---|---|
| `synclab-core/` | The engine. Start with `Run.kt` → `Simulation.kt` → `Network.kt` (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)) |
| `synclab-ktor/`, `synclab-report/`, `synclab-junit5/` | Integrations |
| `examples/` | Realistic app code plus tests that must catch seeded bugs. They double as regression tests for detection power |
| `benchmarks/` | `./gradlew :benchmarks:run` |
| `tools/mutation_test.py` | Mutation testing of core guarantees |
| `buildSrc/` | Gradle conventions shared by modules |

## Rules of the road

1. **Determinism is the product.** Any change that makes the same seed produce a different trace
   must say so in the PR and changelog (see [docs/VERSIONING.md](docs/VERSIONING.md)). Never use
   `Random.Default`, wall clocks or hash-ordered iteration of objects in the engine.
2. **Every fault type needs a bug it catches.** Add or extend an example whose test fails without it.
3. **Tests for guarantees.** If you change the scheduler, network or shrinker, run
   `python3 tools/mutation_test.py` and keep the score at 100%. Add a mutant for any new guarantee.
4. **Public API changes** show up in `api/*.api` files. Run `./gradlew apiDump` and commit the
   diff. Explain why the change is worth it.
5. **Reports are UI.** If you change failure output, paste before/after in the PR.
6. **Docs are part of the change.** Update `docs/CAPABILITIES.md` when what SyncLab can or cannot find changes.

## Commit and PR style

Small PRs with one idea each. Describe the user-visible effect first. CI must be green.

## Reporting bugs

Please include the replay token (`SL1:…`), the failure text, and, if you can, the scenario. A
SyncLab bug that SyncLab can reproduce is the nicest kind of bug.

## Code of conduct

Be kind and assume good intent. Maintainers may remove comments or contributors that make the
project unwelcoming.

## Where to start

See [docs/GOOD-FIRST-ISSUES.md](docs/GOOD-FIRST-ISSUES.md). Extension points that need no core changes:
custom fault types (`network.intercept`), listeners (`SimulationListener`), adapters (any module that
calls `rpc`/`send` from a `NodeScope`), and examples.
