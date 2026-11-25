# Adoption milestones and measurement

Aspirations, not promises. Each lists what raises the odds. Popularity is not adoption: a dependent
repository counts for more than a star.

| Milestone | Product work | Channels / community |
|---|---|---|
| 0.1.0 released, first external users | Publish to Central; fresh-install check against Central; troubleshooting page | Article + Kotlin Slack + newsletters |
| First 10 genuine users (issues, questions, "it found a bug") | Fix every confusing report within days; add their scenario shapes as examples | Answer every issue; GitHub Discussions "Show your invariants" |
| First external contributor | Keep GOOD-FIRST-ISSUES fresh, review within 48 h | Label issues; thank publicly in release notes |
| First independent integration (a repo depending on SyncLab) | Adapters people ask for (Retrofit, WebSockets); Gradle plugin | Integration write-up co-authored with them |
| 100 stars | Second bug story (clock skew / LWW) | r/androiddev, Android Weekly |
| 1,000 stars | Kotest integration, operation shrinking, a docs site | Conference talk proposals (KotlinConf, droidcon), Show HN on DST for clients |
| Sustainable maintenance | Two or more maintainers with merge rights; release automation | Contributor guide, governance note |

## What we measure, privacy-respecting

No telemetry in the library: it runs in test suites and sends nothing anywhere. Signals come from public sources:

- Maven Central download statistics (Central Portal, per month).
- GitHub: stars, forks, **dependents** (the "Used by" graph), issues opened by people outside the project, external PRs merged.
- Install success: issues labelled `install`, plus `tools/fresh-install-check.sh` in CI.
- Doc usability: time to first failing test in a recorded run with a new user (target under 10 minutes).
- Reliability: open bugs labelled `determinism` or `false-positive`. Target zero.

Numbers are reported as read, with their dates. No invented users, testimonials or counts.
