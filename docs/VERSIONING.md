# Versioning and API compatibility

SyncLab follows [Semantic Versioning](https://semver.org/).

## Before 1.0 (now)

- `0.x` minor releases may contain breaking changes. Each one is listed under **Breaking** in
  [CHANGELOG.md](../CHANGELOG.md), with a migration note.
- Patch releases (`0.x.y`) never break source or binary compatibility.

## From 1.0

- **Public API**: everything public in `dev.synclab`, `dev.synclab.ktor`, `dev.synclab.report` and
  `dev.synclab.junit5`, except declarations in `internal` packages or marked `@SyncLabInternal` (none yet).
- **Binary compatibility** is checked on every build by the Kotlin binary-compatibility validator.
  `.api` dump files live in each module's `api/` folder, so a PR that changes public API shows it in the diff.
- Deprecations last at least one minor release (`WARNING`, then `ERROR`, then removal in the next major).

## What counts as a behaviour change

Simulation results are part of the contract. A change that makes **the same seed produce a
different trace** for unchanged user code invalidates saved replay tokens and pinned regression
seeds. Such changes:

- are allowed in minor releases, but never in patches;
- are listed under **Changed: simulation behaviour** in the changelog;
- bump the replay token prefix when the token format itself changes (`SL1` → `SL2`). Old tokens
  then fail with a clear message instead of silently replaying something different.

Adding new fault types is a behaviour change only when they are enabled by default **and** draw
random numbers. New fault types will draw from their own random stream, so existing seeds keep their meaning.

## JSON report schema

`runs.jsonl` and failure JSON carry `"schema": 1`. Fields are only added within a schema version.
Removing or renaming a field bumps the schema number.
