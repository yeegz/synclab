# SyncLab: demand evidence and launch channels

Researched 25 Nov 2026. Reddit, Stack Overflow, and the Stack Exchange API were NOT reachable from the research tooling, so direct "I got duplicate writes" threads there are **unverified/not collected**. Everything below was fetched directly unless marked (U).

## 1. Evidence of pain

| Source | URL | Signal |
|---|---|---|
| DEV post "Hidden problems of offline-first sync: idempotency, retry storms, dead letters" (Mar 31, JS/RN, not Kotlin) | https://dev.to/salazarismo/the-hidden-problems-of-offline-first-sync-idempotency-retry-storms-and-dead-letters-1no8 | Weak-moderate. Names exactly SyncLab's bugs (crash mid-send leaves IN_FLIGHT rows that resend as duplicates; retry storms). 3 comments, ~1 like each. Testing barely discussed. |
| DEV: Flutter outbox pattern; RN offline queue posts | https://dev.to/guimg/flutter-outbox-pattern-16m3 | Weak. Practitioners hand-roll outboxes and fix duplicates by unique idempotency keys; no sim tooling mentioned. |
| Wolt `ktor-idempotency` (Ktor lib for dedup) | https://github.com/woltapp/ktor-idempotency | Weak as demand (11 stars, 45 commits) but proves a real Ktor-side dedup need at a large company. |
| Ktor retry docs | https://ktor.io/docs/client-request-retry.html | Gap evidence. Docs say retry is off by default; they say nothing about idempotency or how to test retry behavior. |
| Ktor perf regression PR with HttpRequestRetry | https://github.com/ktorio/ktor/pull/5071 | Very weak; shows plugin is in real use, not a duplicate-write complaint. |
| Kotlin Slack thread on configuring HttpRequestRetry | https://slack-chats.kotlinlang.org/t/16365620/i-m-having-problem-configuring-httprequestretry-correctly-th | Weak (U: rate-limited, content unread; title only). |
| OkHttp "quietly retrying requests" (Medium, Inloop) | https://medium.com/inloop/okhttp-is-quietly-retrying-requests-is-your-api-ready-19489ef35ace | Weak (U: 403, snippet title only). Suggests Android devs are surprised by silent retries. |
| HN "Downsides of Offline First" (RxDB) | https://rxdb.info/downsides-of-offline-first.html | Moderate. 355 pts / 208 comments (2021): offline-first conflict/sync complexity is a live debate. |
| HN "Offline-First Database Comparison" | https://github.com/pubkey/client-side-databases | Moderate. 415 pts / 155 comments (2021). |
| HN "Local-first software" (Ink & Switch) | https://www.inkandswitch.com/essay/local-first/ | Strong interest in the topic: 883 pts / 297 comments (2025 repost); ElectricSQL Show HN 617 pts / 170 comments (2023). Interest in sync, not testing. |
| PowerSync-kotlin issues (search "duplicate") | https://github.com/powersync-ja/powersync-kotlin/issues | None found. 13 results, none about duplicates or testing. Honest null. |
| Ktor GitHub search "HttpRequestRetry POST duplicate" | https://github.com/ktorio/ktor | None found. No issue titled around non-idempotent retry surfaced. Null. |
| HN comments for "testing offline sync" | (Algolia, 547 hits) | Very weak; top hits were unrelated one-off remarks. No dedicated "how do I test offline-first" thread found. |
| Reddit r/androiddev, r/Kotlin, r/ExperiencedDevs; Stack Overflow; Room/WorkManager/Realm/Firebase/Replicache/Automerge/SQLDelight issues | n/a | **Not collected (U).** Blocked to tooling; do these by hand (query ideas: "WorkManager retry duplicate", "Room outbox pattern test", "offline sync flaky test", "last write wins clock skew"). |

Read: the pain (duplicates after lost responses, crash mid-send, retry storms) is widely written about as a design problem. I found almost no developers asking for a test tool. That is the key gap.

## 2. DST interest trend

HN "deterministic simulation testing" stories (Algolia, points / comments):
- WarpStream "DST for our entire SaaS", Mar 2024: 203 / 26. https://www.warpstream.com/blog/deterministic-simulation-testing-for-our-entire-saas
- S2 "DST for async Rust", Apr 2025: 107 / 10. https://s2.dev/blog/dst
- "DST Is for Everyone", 20 Nov 2026: 89 / 20. https://outdata.net/blog/261120
- Phil Eaton "What's the big deal about DST?", Aug 2024: 84 / 14.
- Polar Signals (Rust, Jul 2025): 39 / 6; "DST for a word processor" (Mar 2024): 36 / 3.
- Antithesis: "How Antithesis finds bugs" 160 / 53 (Apr 2024); "Hypothesis, Antithesis, synthesis" 285 / 104 (Mar 2026); Cockroach "demonic nondeterminism" 138 / 29.
- TigerBeetle simulator 24 / 8 (Nov 2025); protocol-aware DST post only 13 / 0 (Aug 2026, reposted, low).
- Martin Fowler site article "Deterministic Simulation Testing" by Unmesh Joshi, 17 Nov 2026 (HN: 5 pts). It covers server-side replication only; no mobile or client sync. https://martinfowler.com/articles/deterministic-simulation-testing.html
- Stripe Sessions 2025 talk "Testing 2.0: full-system simulations"; Antithesis BugBash talks (found via search, not opened).

Trend: sustained, growing, mainstream (Fowler endorsement, Nov 2026), but **every popular item is server/database/Rust/Go**. Typical HN score is 30-200, so a good DST post is a plausible front-page candidate; a library launch is far weaker. No client-side/mobile DST content found: that is white space and also a sign the audience has not been taught the concept.

## 3. Channels

| Channel | How to submit | Rules | Fit |
|---|---|---|---|
| Show HN | Title "Show HN: ..." at news.ycombinator.com/submit | Must be runnable by readers, ideally no signup; built by you; not a landing page/blog post; no asking friends to upvote; be present in comments. https://news.ycombinator.com/showhn.html | Best reach, poor for Kotlin. Kotlin Show HNs: Signum KMP 10 pts, MapLibre Compose 5, FaKeTime (fake time for tests) 2. Lead with a DST/bug-find story, not "Kotlin library". |
| r/Kotlin, r/androiddev | Link or text post | **Rules unverified (U)**; Reddit unreachable. Check sidebar. General Reddit norm: roughly 10% self-promo, be a participant first. | Core audience. Post a concrete bug story. |
| Kotlin Slack | Join via https://surveys.jetbrains.com/s3/kotlin-slack-sign-up; channels such as #announcements, #feed, #coroutines, #ktor, #multiplatform | **Channel names/rules unverified (U)**; archive returned 429. Read each channel's pinned rules. | High: Ktor and Kotlin team members read it. Ask in #ktor about retry idempotency testing. |
| Kotlin Weekly | Form at https://www.kotlinweekly.net/ or mailinglist@kotlinweekly.net, subject "Link for submission - Kotlin Weekly" | No stated guidelines; ~22.9k subscribers; weekly (Sunday); one sponsored slot. | High; submit a blog post, not the repo. |
| Android Weekly | "Submit" section at https://androidweekly.net/ | Editors choose links; cannot include all; 80k+ readers claimed. | High if framed for Android. Libraries section accepts repo links; articles do better. |
| JetBrains Kotlin newsletter / blog | No public submit path found (U) | n/a | Low unless via klibs.io or a JetBrains contact; do not count on it. |
| awesome-kotlin (KotlinBy) | PR editing `src/main/resources/links/<category>.awesome.kts` (https://raw.githubusercontent.com/KotlinBy/awesome-kotlin/master/.github/contributing.md) | No published criteria; 11.4k stars. | Moderate; cheap one-time. Expect a maintainer to want some stars/maturity (U). |
| awesome-kotlin-multiplatform, awesome-android-testing | Not checked (U) | Unknown | Low effort; check each repo's CONTRIBUTING. |
| klibs.io | Auto-index from Maven Central; or `index-request` GitHub issue; or klibs-io-notifier Gradle plugin. Status: https://klibs.io/package/GROUP/ARTIFACT/VERSION/status | Needs open-source GitHub repo, a Maven Central artifact (also on Google's mirror), at least one multiplatform artifact with `kotlin-tooling-metadata.json`, POM with GitHub `url`/`scm.url`. JVM-only and Gradle plugins not indexed. https://klibs.io/faq | Required for KMP discoverability (4,200 projects as of Aug 2026 per JetBrains blog). Must publish real KMP targets. |
| Maven Central | Central Portal account, verified namespace, signed artifacts | Per KMP docs https://kotlinlang.org/docs/multiplatform-publish-lib.html (not read in detail). | Prerequisite for everything. |

## 4. Positioning lessons

- **Turbine** (cashapp, 2.9k stars): tagline names the pain it fixes ("testing library for kotlinx.coroutines Flow"); a 4-line example is the first thing after the intro; download comes second; then usage by concrete scenario. Backed by Cash App, so adoption was credibility-driven.
- **Lincheck** (JetBrains; the stars figure I fetched, 705, is likely stale): README opens with a **failing test and its printed interleaving trace** (`expected:<2> but was:<1>`), then finds a real bug in Java's ConcurrentLinkedDeque. Backed by a CAV '23 paper and a KotlinConf '23 talk. Closest analogue to SyncLab: "show the bug it finds" beats feature lists.
- **MockK** (5.8k): getting-started snippets first, then DSL example; known-issues section is up front; framework integrations (Spring) and multilingual docs spread it. Adopted as the Kotlin-idiomatic Mockito replacement.
- **Kotest** (4.8k): broad framework, community channel in Kotlin Slack, website docs; plugin ecosystem.
- Pattern: pain-named tagline, shortest-possible failing example, copy-paste Gradle snippet, Kotlin Slack channel, one conference talk or paper. Adoption of Turbine and MockK came from fixing a daily annoyance; SyncLab's problem is rarer and costlier, so it needs the Lincheck-style "here is a real bug it found" proof.

## 5. Honest assessment of demand

**Moderate-to-weak today; strong latent potential.**
- For: DST interest is real and rising (Fowler, 89-pt HN post last week); offline-first and local-first draw large HN threads (300-900 pts); the duplicate/outbox/idempotency problem is widely documented; Ktor's own retry docs ignore idempotency.
- Against: I found no developer explicitly asking "how do I test offline sync" and zero direct GitHub issues on duplicate-after-retry in PowerSync/Ktor (Reddit/SO unchecked, so absence there is not proof). Wolt's idempotency lib has 11 stars. Kotlin Show HNs score 1-10 points. The target user (Android/KMP dev with a hand-rolled outbox) is a small slice, and most will not know they need DST until shown a bug.
- Implication: demand must be created through education and bug demonstrations, not satisfied by an existing search habit.

## 6. Recommended positioning

**One sentence:** "Run your real Kotlin sync code (outbox, Ktor retries, Room) through thousands of seeded fault schedules in virtual time, and get the minimal failing sequence plus a one-line replay."

- **Primary audience:** KMP/Android engineers who built their own offline-first outbox or use Ktor `HttpRequestRetry` against a non-idempotent API and own money-, order-, or record-creating writes (field service, fintech, health, POS).
- **Secondary audience:** Authors of sync libraries and backend-for-mobile teams; engineers who already read DST content (HN/TigerBeetle/Antithesis readers) and want it client-side.
- **Launch order suggestion:** (1) publish a "we found N duplicate-write bugs in popular samples/our own app" post with failing-test-first README; (2) Kotlin Slack #ktor and #multiplatform; (3) Kotlin Weekly and Android Weekly with the post; (4) r/Kotlin after reading sidebar; (5) Show HN only after a runnable demo and a DST-framed title; (6) klibs.io and awesome-kotlin once on Maven Central.

## Unverified summary
Reddit/SO threads and rules; Kotlin Slack channel names and rules; JetBrains newsletter path; awesome-kotlin criteria; awesome-android-testing and awesome-KMP; Maven Central steps in detail; Medium/OkHttp article content; Lincheck star count.
