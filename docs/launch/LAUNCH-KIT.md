# Launch kit (drafts, nothing posted)

Every item here needs Yousof's explicit approval before it goes anywhere, one post at a time.
Prerequisites: public repo, 0.1.0 on Maven Central, install verified from Central (not mavenLocal).

## Order

1. **Article** (own blog or dev.to): "The retry that charged twice: finding a sync bug with deterministic simulation in Kotlin".
2. **Kotlin Slack** (`#feed`; check channel rules first): two lines and the article link.
3. **Kotlin Weekly** and **Android Weekly**: submit the article through their forms.
4. **r/Kotlin**, then **r/androiddev** (check self-promotion rules on the day; reply to every comment).
5. **awesome-kotlin** PR (`.awesome.kts` entry) and **klibs.io**, once on Central.
6. **Show HN**, framed around DST for client apps, only once the article has had feedback.

## 1. Article outline

1. The bug: a shared-expense app. A phone adds "Dinner 43.75", the server saves it, the reply is
   lost, the app retries, and the bill is now 87.50. Real, common, invisible on Wi-Fi.
2. Why tests missed it: mocks always succeed, and real-network tests are slow and flaky.
3. Ten lines of SyncLab: a server node, a phone node, `eventuallyEqual`, a random workload.
4. The report, verbatim, from `examples/expense-ledger`: "Reproduces with only RESPONSE_LOSS",
   the story, the replay line. Include the relay-lanes screenshot.
5. The fix (an op id plus an idempotency receipt written with the change), and 200 hostile seeds passing in about 1.4 s.
6. Bonus: clock skew loses edits under last-writer-wins (`examples/offline-tasks`), and how a hybrid logical clock fixes it.
7. Limits: link CAPABILITIES.md. What it does not prove.
8. Try it: install, quick start, examples.

## 2. Kotlin Slack (#feed)

> I built SyncLab, a deterministic simulation testing library for Kotlin sync code. It runs your
> real clients and server logic (including a Ktor HttpClient) through seeded lost replies,
> duplicates, partitions, crashes and clock skew on virtual time, then shrinks a failure to the
> faults it needs, plus a one-line replay. Write-up of a duplicate-charge bug it finds: <link>. Feedback very welcome.

## 3. Newsletter blurb (Kotlin Weekly / Android Weekly)

> **SyncLab: deterministic simulation testing for Kotlin sync code.** Find the bugs that only
> happen when a reply is lost, doubled or late: seeded faults, virtual time, business invariants,
> minimal failing fault sets and one-line replays. <link>

## 4. r/Kotlin post

Title: *I wrote a test library that finds "saved twice" bugs in offline sync code (deterministic, seeded, KMP)*

Body: the bug story in four sentences, the 12-line test, the report excerpt, what it can't do
(link CAPABILITIES.md), and a direct question: "If you have an outbox or Ktor retries on writes,
I'd love to know whether this finds anything in your code." No vote requests.

## 5. Show HN

Title: *Show HN: SyncLab – deterministic simulation testing for mobile/offline sync code (Kotlin)*

First comment (author):
> Deterministic simulation testing has been mostly a server and database thing (FoundationDB,
> TigerBeetle, turmoil). The same bugs show up in client sync code: a reply lost after the server
> committed, a retry that mints a new id, last-writer-wins on skewed phone clocks. SyncLab runs the
> real Kotlin client and server code on one virtual clock with seeded faults, checks invariants after
> every step, and shrinks a failure to the fault types it needs. It doesn't touch real sockets or
> threads (details in CAPABILITIES.md). Happy to answer questions about the scheduler design.

## 6. LinkedIn (Yousof's voice; plain, no hype)

> Most sync bugs I've hit weren't logic errors. They were timing: the server saved, the phone never
> heard back, and it retried. I built SyncLab, an open-source Kotlin library that runs real sync code
> through thousands of seeded failure schedules in virtual time and hands back the smallest set of
> faults that breaks it, with a one-line replay. 200 hostile runs of a four-device expense ledger
> take about 1.4 seconds. Write-up and code: <link>

## Assets

- Social preview: `docs/brand/social-preview.png` (1280×640); set it as the GitHub social image.
- Icon: `docs/brand/icon.png` / `icon.svg`; mark: `docs/brand/mark.svg`.
- Screenshots: `docs/images/report-relay-lanes.jpg`, `docs/images/report-dark.jpg`.
- Report to show live: run `./gradlew :examples:expense-ledger:test`, then open `examples/expense-ledger/build/synclab-reports/failures/*.html`.

## GitHub metadata (for when the repo is public)

- Description: "Deterministic simulation testing for Kotlin sync code: seeded lost replies, duplicates, partitions, crashes and clock skew on virtual time, with minimal failing fault sets and one-line replays."
- Topics: `kotlin`, `kotlin-multiplatform`, `testing`, `deterministic-simulation`, `offline-first`, `local-first`, `sync`, `fault-injection`, `property-based-testing`, `ktor`, `distributed-systems`, `chaos-engineering`.
