# Benchmarks

Measured with `./gradlew :benchmarks:run` (source: [`benchmarks/`](../benchmarks/src/main/kotlin/dev/synclab/bench/Benchmarks.kt)).
These are plain wall-clock measurements: warm-up runs, then the median of 5 to 7 runs. They are not
JMH. They are meant to show order of magnitude and to catch regressions, not to tune micro-optimisations.

Machine: Apple Silicon laptop (8 cores, macOS, arm64), JDK 17.0.19, single-threaded simulation. Date: 2026-11-25.

| Benchmark | Median time | Throughput |
|---|---|---|
| Scheduler: 100,000 coroutines × 1 `delay` (SyncLab) | 222.9 ms | 448,577 tasks/s |
| Scheduler: same workload in `kotlinx-coroutines-test` `runTest` (baseline) | 446.2 ms | 224,117 tasks/s |
| RPC round trips, perfect network (10,000 calls) | 64.9 ms | 154,019 calls/s |
| RPC with retries until success, hostile network (10,000 ops) | 120.1 ms | 83,264 ops/s |
| `explore`: 100 runs, 3 clients × 20 ops, flaky network | 21.9 ms | 4,570 runs/s |
| `explore`: same, with determinism sampling (every 10th seed re-run) | 19.5 ms | 5,119 runs/s |
| `explore`: 20 runs, 10 clients × 50 ops, hostile network + disconnect chaos | 178.6 ms | 112 runs/s |
| Find and shrink a duplicate-delivery bug (100 seeds max, then shrink) | 117.9 ms | 8 failures/s |

Sanity check: the 100-run `explore` executed 26,288 scheduler steps (about 4 per RPC: send, deliver,
reply, resume), and 1,499 s of virtual time. Every one of `DROP`, `DUPLICATE`, `RESPONSE_LOSS`,
`REQUEST_FAILURE` and `REORDER` fired in 73 to 88 of the 100 runs.

## What this means in practice

- **Framework overhead per scheduled task is lower than `kotlinx-coroutines-test`'s** on this
  workload (about 2.2 µs vs 4.5 µs), even though SyncLab also checks invariants and records a trace.
- A realistic four-device ledger scenario (the expense-ledger example) runs **200 hostile runs with
  crashes and partitions in about 1.4 s**. A PR build can afford hundreds of seeds per test, and a
  nightly job tens of thousands.
- The determinism check (one extra run per 10 seeds) costs within measurement noise here (the
  sampled run was not slower in this measurement; expect about +10% in general).
- Shrinking costs at most one re-run per fault type that fired (usually 4 to 8 re-runs), plus one
  re-run to confirm the failure.

## Memory

Heap in use after the whole benchmark suite: about 3 MB. Each run keeps at most `traceLimit` events
(default 20,000) in memory, and nothing outlives a run.

## Reproduce

```bash
./gradlew :benchmarks:run -Psynclab.native=false
```

The nightly CI workflow runs the benchmarks too, so regressions show up in its logs.
