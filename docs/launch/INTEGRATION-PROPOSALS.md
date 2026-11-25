# Integration proposals (prepared, not sent)

No unsolicited PRs. Each of these becomes a runnable example in *this* repo first. We'd contact a
maintainer only if the example finds something real, or if they ask, and only with Yousof's approval.

| Project | Why it could genuinely benefit | First step here |
|---|---|---|
| **Store5** (MobileNativeFoundation/Store, KMP offline-first repository) | Its `Updater`/`Bookkeeper` handles writes made offline and failed syncs: exactly the lost-reply and retry space | `examples/store5-sync`: drive Store5's updater through `rpc`, assert writes land exactly once after crashes |
| **ktor-idempotency** (Wolt) | A server-side idempotency plugin; SyncLab can show it under duplicates and lost replies | Example that routes a Ktor client to a simulated handler using the plugin's semantics |
| **PowerSync Kotlin SDK** | Its docs leave upload idempotency to the app backend, so a SyncLab template for that backend contract helps its users | "Testing your PowerSync upload handler" guide and example |
| **Supabase-kt / Realm Kotlin users** | Apps with their own retry layers on top | Docs recipe only |

## Yousof's projects (inspected read-only)

Photoshoot, Bupples, Unloop, Planket and the other current projects are TypeScript or Flutter/Dart,
with no Kotlin sync code (only Android stubs). SyncLab can't be wired into them directly. Bupples'
ledger rules (op ids, idempotency receipts committed with the write, integer minor units, confirm-time
revalidation) shaped the independently written `examples/expense-ledger`. A Dart port of the core
ideas is a possible future project; it is not planned.
