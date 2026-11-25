# Quick start

Goal: in five minutes, write a test that finds a real duplicate-write bug in a tiny sync client,
then fix it and watch the same test pass on 500 different simulated worlds.

The code on this page is compiled and run in CI as
[`QuickstartTest.kt`](../synclab-core/src/jvmTest/kotlin/dev/synclab/docs/QuickstartTest.kt), so it stays correct.

## 1. Add the dependency

```kotlin
// build.gradle.kts (JVM or Android module; for KMP use commonTest)
dependencies {
    testImplementation("io.github.yeegz.synclab:synclab-core:0.1.0-SNAPSHOT")
    testRuntimeOnly("io.github.yeegz.synclab:synclab-report:0.1.0-SNAPSHOT") // HTML + JSON reports, no setup
}
```

## 2. The code under test

A counter service and a client that retries on network errors. This is the kind of code that works
every time on a good connection:

```kotlin
class CounterServer {
    var total = 0
    fun add(amount: Int) { total += amount }
}

class CounterClient(private val send: suspend (Int) -> Unit) {
    suspend fun add(amount: Int) {
        while (true) {
            try { send(amount); return } catch (e: SimulatedNetworkException) { delay(200) }
        }
    }
}
```

In a real app, `send` would be your HTTP call and you'd catch `IOException`. SyncLab only needs a
place to plug in: a function, an interface or a Ktor engine. You don't restructure the app.

## 3. The test

```kotlin
class CounterTest {
    @Test
    fun `every tap is counted exactly once`() {
        explore(runs = 500) {                                    // 500 seeds = 500 different worlds
            val server = node("server") {
                CounterServer().also { s -> serve<Int, Unit> { _, amount -> s.add(amount) } }
            }
            val phone = node("phone") {
                val call = rpc<Int, Unit>("server")
                CounterClient { amount -> call(amount) }
            }

            var taps = 0
            eventuallyEqual("server total equals taps", { taps }, { server.app.total })

            repeat(random.nextInt(1, 20)) {                     // a different workload per seed
                taps++
                phone.perform("tap") { add(1) }
            }
        }
    }
}
```

What happens:

- `node(...)` creates a simulated device. Its block builds your app object; `rpc` and `serve` connect it to the network.
- `explore` runs the scenario for seeds 1..500. Each seed fixes the workload *and* every network decision.
- The default network is `NetworkConditions.Flaky`: some loss, duplicates, lost responses and reordering.
- After the scenario, SyncLab heals the network, waits for the system to settle, then checks `eventually…`.

## 4. Run it

```
./gradlew test
```

It fails within a few seeds:

```
SyncLab found a problem (seed 2, at t=3.36s of virtual time).

  Did not converge: server total equals taps
    The system went idle (nothing left to run) without getting there: nothing will retry or resync.
      expected: 9
      actual:   10

  Reproduces with only these fault types: RESPONSE_LOSS  (minimal for this seed: turning any one off hides it)
  ...
  Reproduce exactly:
    SYNCLAB_REPLAY=SL1:2:INTERLEAVING+REORDER:2f1c9a0e ./gradlew test --tests "CounterTest"
```

Read it as a story: the server applied a tap, the reply was lost, and the client retried, so the tap
was counted twice. The shrinker re-ran the seed with other fault types turned off; only
`RESPONSE_LOSS` is needed to reproduce it.

Open `build/synclab-reports/failures/*.html` to see the whole run as a timeline with one lane per device.

## 5. Fix it

Give every tap an id that the client keeps across retries, and make the server remember what it applied:

```kotlin
class IdempotentClient(private val send: suspend (id: String, amount: Int) -> Unit) {
    private var next = 0
    suspend fun add(amount: Int) {
        val id = "tap-${next++}"                // chosen once, reused by every retry
        while (true) {
            try { send(id, amount); return } catch (e: SimulatedNetworkException) { delay(200) }
        }
    }
}

class IdempotentServer {
    var total = 0
    private val applied = HashSet<String>()
    fun add(id: String, amount: Int) { if (applied.add(id)) total += amount }
}
```

In the test, wire them with `serve<Pair<String, Int>, Unit> { _, (id, amount) -> s.add(id, amount) }` and
`IdempotentClient { id, amount -> call(id to amount) }`.

Run again: 500 seeds pass in well under a second. To check the fix is really what made the
difference, replay the old failure with `SYNCLAB_REPLAY=...`. SyncLab tells you if the run no longer matches.

## 6. Where to go next

- Add `always("…") { … }` invariants. These are checked after **every** step, not just at the end.
- Turn up the pressure: `SimConfig { network = NetworkConditions.Hostile; clockSkew = 5.seconds; chaos { crashes(every = 5.seconds..20.seconds) } }`.
- Run more seeds nightly: `SYNCLAB_RUNS=20000 ./gradlew test` (see [CI.md](CI.md)).
- Using Ktor? Inject `httpEngine("server")` into your existing `HttpClient(engine) { … }` (see [ADOPTING.md](ADOPTING.md)).
- Read what SyncLab can and cannot find: [CAPABILITIES.md](CAPABILITIES.md).
