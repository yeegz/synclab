# SyncLab in CI

## Defaults are CI-safe

- Seeds are **fixed** (`firstSeed = 1`), so the same commit always runs the same worlds. A red build
  is reproducible locally with no extra steps.
- Runs need no network, ports, containers or services.
- Every failure prints a replay command, and with `synclab-report` on the test classpath, writes
  `build/synclab-reports/failures/*.html|json`.

## Settings

| Environment variable | System property | Effect |
|---|---|---|
| `SYNCLAB_RUNS=5000` | `synclab.runs` | Overrides `runs` in every `explore` call |
| `SYNCLAB_FIRST_SEED=123` / `random` | `synclab.firstSeed` | Start elsewhere, or at a random seed (nightly fuzzing) |
| `SYNCLAB_REPLAY=SL1:…` | `synclab.replay` | Run exactly one recorded failure |
| `SYNCLAB_REPORT_DIR=…` | `synclab.reportDir` | Where reports go (default `build/synclab-reports`) |
| `SYNCLAB_REPORTS=off` | `synclab.reports=off` | Disable report files |

Gradle passes environment variables to test JVMs, so `SYNCLAB_RUNS=5000 ./gradlew test` just works.
System properties need forwarding in `tasks.test { systemProperty(...) }`.

## GitHub Actions

PR builds run the default seeds. A nightly job runs many more from a random starting point, and
uploads reports when anything fails:

```yaml
name: sync-simulation
on:
  pull_request:
  schedule: [{ cron: "17 3 * * *" }]

jobs:
  simulate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - name: Simulation tests
        run: ./gradlew test
        env:
          SYNCLAB_RUNS: ${{ github.event_name == 'schedule' && '20000' || '' }}
          SYNCLAB_FIRST_SEED: ${{ github.event_name == 'schedule' && 'random' || '' }}
      - name: Upload SyncLab reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: synclab-reports
          path: "**/build/synclab-reports/"
```

On GitHub Actions, failures are also added to the job summary (`GITHUB_STEP_SUMMARY`) with the replay command.

## Machine-readable output

`build/synclab-reports/runs.jsonl` has one line per `simulate`/`explore` call:

```json
{"schema":1,"test":"example.ledger.LedgerSyncTest.correct ledger converges","passed":true,"runs":200,"firstSeed":1,
 "totalSteps":32415,"totalVirtualMillis":4538602,"wallMillis":1390}
```

Failures include a `failure` object: `kind`, `name`, `message`, `seed`, `replayToken`,
`faultsInvolved`, `minimised`, `trace[]`. The schema is versioned (`schema`), and fields are only
added within a version.

Gradle's own JUnit XML is unchanged, and the failure text is the assertion message, so existing test
dashboards show SyncLab failures with no extra setup.

## Turning a nightly failure into a regression test

1. Copy the replay token from the job summary or `runs.jsonl`.
2. Reproduce locally: `SYNCLAB_REPLAY=SL1:… ./gradlew test --tests "…"`.
3. Fix the bug, then pin the seed as a permanent test:

```kotlin
@Test fun `regression: duplicate confirm (nightly 2026-11-25)`() = simulate(seed = 4821337) { ledgerScenario() }
```
