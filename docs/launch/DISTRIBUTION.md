# Distribution plan

SyncLab is a Kotlin library used in test source sets, so it goes where Kotlin developers already
get libraries. Channels like Homebrew, WinGet, npm, Docker or PyPI don't fit and aren't planned.

| Channel | Why | Status | What's needed |
|---|---|---|---|
| **Maven Central** (`io.github.yeegz.synclab:*`) | The default for every Gradle/Maven user; KMP metadata included | Ready to publish, **not published** | Owner approval, Central Portal namespace `io.github.yeegz`, GPG key (see RELEASING.md) |
| **GitHub Releases** | Changelog, source archive and checksums for people who audit | Not created | Public repo (owner approval); tag `v0.1.0` |
| **klibs.io** (JetBrains KMP library directory) | Where KMP developers browse libraries | Not listed | Indexes public GitHub repos with KMP artifacts on Maven Central; needs both above |
| **Dokka API reference on GitHub Pages** | Search-indexed API docs | Generated locally (`./gradlew :dokkaGenerate`) | Public repo + Pages (owner approval) |
| `mavenLocal()` | Try it today without any publishing | **Verified** (see below) | `./gradlew publishToMavenLocal` |

## Verified install path (today)

`./gradlew publishToMavenLocal` publishes all four modules for every target. A separate sample
project in a fresh directory that uses only `mavenLocal()` + `mavenCentral()` resolves
`synclab-core`, `synclab-junit5` and `synclab-report`, compiles, and runs a failing and a passing
simulation. See [`tools/fresh-install-check.sh`](../../tools/fresh-install-check.sh), which recreates
that project from scratch each time.

## Release automation (prepared, inactive)

- `.github/workflows/ci.yml`: tests (JVM, JS, macOS/iOS native), API compatibility check, Dokka, mutation testing.
- On a version tag, a release job would run `publishToMavenCentral` with secrets. It is deliberately
  not added until the owner sets up credentials and approves the first release.
- Artifacts are signed (`signAllPublications`), and Central requires sources and javadoc jars. The vanniktech plugin produces both.

## Licences and notices

- SyncLab: Apache-2.0 (`LICENSE`).
- Embedded fonts in `synclab-report`: SIL OFL 1.1 (`synclab-report/FONTS-LICENSE.txt`). The OFL allows embedding in software.
- Runtime dependencies: kotlinx-coroutines (Apache-2.0); Ktor and kotlinx-serialization (Apache-2.0) only in their modules; JUnit (EPL-2.0) only in `synclab-junit5`, as an API dependency.
