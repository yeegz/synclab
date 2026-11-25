# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows [SemVer](https://semver.org/)
with the simulation-behaviour rules in [docs/VERSIONING.md](docs/VERSIONING.md).

## [Unreleased]

### Added
- `synclab-core`: deterministic virtual-time dispatcher with seeded same-instant interleavings; simulated
  network (RPC + one-way messages) with drop, duplicate, response loss, fast failure, latency spikes,
  reordering, disconnects and partitions; node crash/restart with durable storage; per-node clock skew and
  jumps; background chaos; custom fault types via interceptors; `always`, `alwaysEqual`, `eventually`,
  `eventuallyEqual`, `converges`; `simulate` and `explore`; fault-type shrinking; replay tokens; trace
  fingerprints; sampled determinism verification; escaped-dispatcher detection; failure reports.
- `synclab-ktor`: `httpEngine`/`httpClient` routing a real Ktor client over the simulated network; `serveHttp` router.
- `synclab-report`: JSON Lines run log, failure JSON, self-contained HTML viewer, GitHub job summary, zero-config discovery.
- `synclab-junit5`: `@SyncLabTest` / `SyncLabExtension` for exact test names.
- Examples: expense ledger, offline task manager, collaborative text, Ktor inventory sync.
- Benchmarks, mutation testing script, documentation.
