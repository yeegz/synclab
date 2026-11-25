# Adopting SyncLab in an existing app

SyncLab does not ask you to restructure your app. It needs three seams that most sync code already
has, or can get with a constructor parameter:

| Seam | Usually already exists as | In the test, pass |
|---|---|---|
| **Transport** | An API interface, a Retrofit/Ktor service, or `HttpClient(engine)` | an adapter around `rpc(...)`, or `httpEngine("server")` |
| **Time** | `System.currentTimeMillis()`, `Clock.System` | `clock::nowMillis` (per-node, skewable) |
| **Randomness / ids** | `UUID.randomUUID()`, `Random.Default` | `{ random.nextId() }` (seeded, per node) |

Plus two things your code must allow:

- **Dispatchers.** Sync code should launch on the scope or dispatcher it is given. Under SyncLab
  that is the node's scope (the `NodeScope` receiver). Hard-coded `Dispatchers.IO` escapes virtual
  time, and SyncLab reports that as `ESCAPED_DISPATCHER` on the JVM.
- **Persistence.** Whatever your app keeps on disk (outbox, cursor, local DB) should come from
  something you can hand in, so the test can give it `storage` to survive crashes.

## Step by step

### 1. Wrap your transport

If your client depends on an interface:

```kotlin
interface SyncApi { suspend fun push(ops: List<Op>): Ack; suspend fun pull(cursor: Long): Changes }

// test-only adapter: about five lines
class SimSyncApi(private val push: RpcClient<List<Op>, Ack>, private val pull: RpcClient<Long, Changes>) : SyncApi {
    override suspend fun push(ops: List<Op>) = push.invoke(ops)
    override suspend fun pull(cursor: Long) = pull.invoke(cursor)
}
```

If your client uses Ktor, inject the engine you already pass to `HttpClient`:

```kotlin
// production
fun createHttpClient(engine: HttpClientEngine = OkHttp.create()) = HttpClient(engine) { install(HttpRequestRetry) { … } }

// SyncLab
val phone = node("phone") { MyApiClient(createHttpClient(httpEngine("server"))) }
```

Your plugins (JSON, retry, timeouts, auth) run unchanged and on virtual time.

### 2. Put your server logic behind `serve`

Server code is usually a function of (request) → response over some state. Run that function, not
the web framework:

```kotlin
val server = node("server", crashable = false) {
    LedgerService(InMemoryRepo()).also { svc -> serve<PushRequest, PushResponse>("push") { _, req -> svc.push(req) } }
}
```

With Ktor on the server you can map routes with `serveHttp { post("/push") { req -> … } }`, calling
into the same service class your Ktor routes call.

### 3. Model persistence explicitly

```kotlin
node("phone") {
    val db = storage.getOrPut("db") { InMemoryLocalDb() }   // survives crash()
    SyncClient(api, db, clock = clock::nowMillis, ids = { random.nextId() }).also { c ->
        every(5.seconds) { runCatching { c.sync() } }       // your periodic sync loop, cancelled on crash
    }
}
```

Anything created outside `storage` is lost on `crash()`, just like process memory.

### 4. Write the invariants your business cares about

Start with three questions:

1. **What must never be true, even for a moment?** Use `always`: money conservation, no negative stock,
   no duplicate ids, cache equals recomputed aggregate.
2. **What must users eventually see?** Use `eventually` / `eventuallyEqual`: every action lands
   exactly once, outboxes drain.
3. **Who must agree?** Use `converges`: devices match the server.

### 5. Start gentle, then turn it up

```kotlin
explore(runs = 100, config = SimConfig { network = NetworkConditions.Perfect }) { … }   // baseline: logic bugs only
explore(runs = 300) { … }                                                              // Flaky (default)
explore(runs = 300, config = SimConfig {
    network = NetworkConditions.Hostile
    clockSkew = 5.seconds
    chaos { disconnects(every = 2.seconds..10.seconds); crashes(every = 5.seconds..30.seconds) }
}) { … }
```

## Common migration issues

| Symptom in the report | Cause | Fix |
|---|---|---|
| `Work escaped the simulation` | `withContext(Dispatchers.IO)` in sync code | Inject a dispatcher; use the node scope in tests |
| `Nondeterministic code under test` | `Random.Default`, `UUID.randomUUID()`, `System.currentTimeMillis()`, shared singletons, Ktor's retry jitter | Inject randomness, time and state; set `randomizationMs = 0` in tests |
| `Scenario never finished` | Awaiting something with no timeout, or a real callback | Add a timeout or route the callback through the node scope |
| `Misconfigured simulation … nothing serves it` | Service name mismatch, or handler registered outside the node factory | Register `serve` inside the factory so it comes back after restarts |
| `Did not converge … went idle` | Nothing retries after a failure | Add a periodic sync or retry; this is usually a real bug |
