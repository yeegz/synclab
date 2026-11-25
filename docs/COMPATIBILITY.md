# Compatibility

## Platforms

| Module | JVM | Android (unit tests) | JS (Node) | JS (browser) | macOS arm64 | iOS arm64 / simulator arm64 | Linux x64 |
|---|---|---|---|---|---|---|---|
| synclab-core | ✅ tested | ✅ (JVM) | ✅ tested | builds, untested | ✅ built | ✅ built | ✅ built |
| synclab-ktor | ✅ tested | ✅ (JVM) | ✅ built | builds, untested | ✅ built | ✅ built | ✅ built |
| synclab-report | ✅ tested (files) | ✅ (JVM) | JSON/HTML only | JSON/HTML only | JSON/HTML only | JSON/HTML only | JSON/HTML only |
| synclab-junit5 | ✅ tested | ✅ (JVM) | n/a | n/a | n/a | n/a | n/a |

"Tested" means the module's test suite runs on that target in CI. "Built" means it compiles and
publishes, but tests do not yet run there. iOS tests need a simulator, and Linux native tests need a
Linux runner (both are on the roadmap). Android instrumented (on-device) tests are not targeted:
SyncLab is meant for local unit tests.

Platform differences:

- **Foreign-thread detection** (`ESCAPED_DISPATCHER`) works on the JVM and Native. JS has one thread.
- **Automatic test names and `ServiceLoader` reports** are JVM-only. Elsewhere, register listeners with `SyncLab.addListener`.
- **Settings** come from environment variables on all platforms; JVM system properties work too.

## Versions it is built and tested with

| Dependency | Version | Notes |
|---|---|---|
| Kotlin | 2.3.0 | Consumers on Kotlin 2.1+ should work (metadata compatibility), unverified below 2.2 |
| kotlinx-coroutines | 1.10.2 | SyncLab implements `Delay` (`@InternalCoroutinesApi`); pinned by `SchedulerTest` |
| Ktor client | 3.3.0 | `synclab-ktor` builds on `ktor-client-mock`'s `MockEngine` |
| kotlinx-serialization | 1.9.0 | `synclab-report` only |
| JUnit Jupiter | 5.13 | `synclab-junit5` only |
| JDK | 17 to build; bytecode targets 11 | |
| Gradle | 9.1 | |

## Coroutines internals: the one compatibility risk

The dispatcher implements `kotlinx.coroutines.Delay`, as `kotlinx-coroutines-test` does. If a future
coroutines release changes that interface, SyncLab needs a matching release. CI runs the scheduler
tests against the pinned version, and the roadmap includes a CI matrix entry against the latest
coroutines release, to warn early.

## Test frameworks

Any framework that treats `AssertionError` as a failure works: kotlin.test, JUnit 4/5, Kotest (use
`simulate`/`explore` inside a test body), TestNG, Spock. `synclab-junit5` only adds exact test names.
