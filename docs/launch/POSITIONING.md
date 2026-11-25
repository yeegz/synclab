# Positioning

Evidence: [research/RESEARCH.md](../research/RESEARCH.md) (landscape) and [research/DEMAND.md](../research/DEMAND.md) (demand and channels).

## One sentence

**Run your real Kotlin sync code through thousands of seeded failure schedules in virtual time, and get
the smallest set of faults that breaks it plus a one-line replay.**

## Who it's for

- **Primary:** Android and KMP teams with a hand-rolled offline outbox, or Ktor retries on writes
  that aren't idempotent (expenses, orders, messages, edits). They already write unit tests and
  have seen "it got saved twice" in production.
- **Secondary:** authors of Kotlin sync and offline libraries (Store, PowerSync-kotlin,
  Supabase-kt users, Apollo's normalized cache) who want a regression harness, and backend Kotlin
  developers curious about deterministic simulation testing (DST).

## The problem, in their words

"Works on my Wi-Fi." Duplicates after a retry. An edit that "disappeared" because a phone's clock
was behind. Sync tests that are either flaky (real sleeps, real servers) or fake (mocks that always succeed).

## Why the alternatives fall short for this job

| They try | Why it doesn't solve it |
|---|---|
| Mocks / MockWebServer | Only the failures you think to script. No timing, no "processed but reply lost", no duplicates |
| Toxiproxy | Real sockets and real time, so it's slow and non-reproducible. No assertions; can't express "reply lost after commit" |
| Jepsen | Clusters, SSH, Clojure. Built for database vendors |
| kotlinx-coroutines-test | Virtual time, but no network, faults, nodes, invariants or exploration |
| Property testing (Kotest, jqwik) | Generates values, not failure schedules across devices |
| DST tools (turmoil, madsim, FoundationDB) | Excellent, but Rust, C++ or a specific runtime |

## Why someone switches, recommends and returns

- **Switch:** the first test finds a real duplicate-write bug in minutes, in their own code.
- **Recommend:** the failure report is a short story with one replay line, and it is easy to paste into a PR.
- **Integrate:** about five lines of adapter code; a real Ktor client runs unchanged.
- **Return:** nightly runs (`SYNCLAB_RUNS`) keep finding rare bugs, and replay tokens turn them into regression tests.
- **Remember:** "the one that shows the message's journey across the devices."

## Honest demand assessment

Demand is **moderate to weak as an existing search habit, with strong latent potential**. The pain is
real and well documented in design write-ups. Hacker News is warming to DST, but almost no one is
looking for a client-side DST tool yet. Adoption has to be **created with bug-finding stories**
(the Lincheck pattern), not captured from search. Kotlin "Show HN" posts historically score low,
so HN comes last, framed around DST, after the Kotlin channels.

## Messaging rules

- Lead with a bug and its report, not with features.
- Never claim proof of correctness. Say "found", "reproduced", "exercised N seeds".
- Use only numbers from real runs (see docs/BENCHMARKS.md). No adoption claims before there is adoption.
