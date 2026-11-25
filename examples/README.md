# Examples

Each example is plain application code in `src/main` (no SyncLab imports) and SyncLab tests in `src/test`.
The tests check two things: the correct implementation passes hundreds of hostile worlds, and seeded
bugs are caught with a readable report. Run one with `./gradlew :examples:expense-ledger:test`.

| Example | App code | Bugs SyncLab must catch | Faults that matter |
|---|---|---|---|
| [expense-ledger](expense-ledger) | Offline clients with a durable outbox, op ids, idempotency receipts, payments the payee confirms, integer minor units, largest-remainder splits | Non-idempotent server; new id on every retry; confirming without rechecking status (two devices) | `RESPONSE_LOSS`, `DUPLICATE`, interleaving |
| [offline-tasks](offline-tasks) | Last-writer-wins task list with a pluggable conflict policy | LWW by device wall clock loses causally later edits | `CLOCK_SKEW` (and only that) |
| [collab-text](collab-text) | Relay server with push + anti-entropy pull; a naive index-based doc and an RGA sequence CRDT | Index-based ops diverge | reordering, concurrency |
| [ktor-sync](ktor-sync) | Ktor client with JSON, `HttpRequestRetry`, `HttpTimeout`, `Idempotency-Key` | Server ignoring the key; Ktor's random retry jitter (nondeterminism) | `RESPONSE_LOSS`, `DUPLICATE` |

The expense-ledger invariants (sum-to-zero balances, shares that add up exactly, settled totals that
match confirmed payments, receipts committed with the write, confirm-time revalidation) mirror
correctness rules common in real shared-expense apps. The implementation here is independent.

Reports for failing runs land in `examples/<name>/build/synclab-reports/failures/`.
