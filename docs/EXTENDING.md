# Extending SyncLab

## Custom fault types

A fault type is a name. Give an interceptor one, and SyncLab will inject it, trace it, count it in
coverage, and include it in shrinking:

```kotlin
val CORRUPT = FaultType("CORRUPT")
val STALE_VERSION = FaultType("STALE_VERSION")

explore(runs = 300) {
    network.intercept(CORRUPT) { msg, random ->
        if (msg.kind == MessageKind.REQUEST && msg.payload is PushRequest && random.chance(0.05))
            Verdict.Replace((msg.payload as PushRequest).copy(ops = emptyList()))   // truncated batch
        else Verdict.Pass
    }
    network.intercept(STALE_VERSION) { msg, random ->
        if (msg.to == "server" && random.chance(0.02)) Verdict.Delay(30.seconds) else Verdict.Pass
    }
    …
}
```

Verdicts: `Pass`, `Drop`, `Duplicate`, `Delay(extra)`, `Replace(payload)`. Interceptors run after the
built-in fault decision, in installation order, and are skipped while the system settles. Always use
the `random` you are given, so runs stay reproducible.

To switch one off in a run: `SimConfig { disable(CORRUPT) }`.

## Custom invariants

Invariants are plain lambdas, so any check you can write in Kotlin works. Patterns that read well in reports:

```kotlin
always("stock never negative") { server.app.stock.values.all { it >= 0 } }

// Show both sides on failure:
alwaysEqual("cached total equals recomputed", { server.app.recompute() }, { server.app.cachedTotal })

// Throw for a custom explanation; the message becomes the report text:
always("no payment exceeds the debt at confirmation") {
    server.app.confirmations.forEach { c ->
        check(c.amount <= c.debtAtConfirm) { "Payment ${c.id}: ${c.amount} confirmed against a debt of ${c.debtAtConfirm}" }
    }
    true
}
```

Invariants must not change state. Reading `node.app` while that node is crashed is fine: the check
is skipped for that step.

## Reusable scenario building blocks

Scenarios are suspend extension functions, so you compose them like any Kotlin code:

```kotlin
suspend fun Simulation.ledgerWorld(devices: Int): World { … }
suspend fun Simulation.randomEdits(world: World, count: IntRange) { … }
fun Simulation.ledgerInvariants(world: World) { always(…); converges(…) }

@Test fun converges() = explore(runs = 300) { val w = ledgerWorld(4); ledgerInvariants(w); randomEdits(w, 10..40) }
```

## Adapters for other transports

An adapter turns your transport's call into `rpc(...)` or `send(...)` on the node. The Ktor adapter
(`synclab-ktor`) is about 150 lines and a good template:

1. Convert the outgoing call into an immutable payload object with a readable `toString()`.
2. Call `rpc<Payload, Reply>(to, service)` from the node scope.
3. Map `SimulatedNetworkException` to the exception type your stack expects (for example `IOException`).
4. Make sure the transport's own coroutines use the node's dispatcher (`coroutineContext[ContinuationInterceptor]`).

Ideas for community adapters: gRPC-Kotlin channels, Retrofit `Call.Factory`, WebSocket sessions
(one-way messages), Firebase/Supabase client fakes, SQLDelight in-memory drivers backed by `DurableStorage`.

## Listeners and custom reports

Implement `SimulationListener` and register it with `SyncLab.addListener(...)`, or, on the JVM, list
it in `META-INF/services/dev.synclab.SimulationListener` to load it automatically. `RunSummary` carries
pass/fail, totals and the full `FailureReport`. `synclab-report`'s `SyncLabJson` gives a stable JSON form.
