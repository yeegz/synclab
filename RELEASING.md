# Releasing

> Nothing is published yet. Publishing to Maven Central is an external, irreversible action and
> needs the owner's explicit go-ahead for each release.

## One-time setup (owner)

1. Create a Sonatype Central Portal account and verify the namespace `io.github.yeegz` (the GitHub
   account proves ownership; no domain needed). To publish under another namespace, change `GROUP` in
   `gradle.properties`.
2. Create a GPG key for signing, and publish the public key to a keyserver.
3. Add these to `~/.gradle/gradle.properties` (never commit them):

```properties
mavenCentralUsername=<portal token user>
mavenCentralPassword=<portal token password>
signingInMemoryKey=<ascii-armored private key>
signingInMemoryKeyPassword=<key password>
```

Signing only turns on when `signingInMemoryKey` is present, so local builds and `publishToMavenLocal`
work without keys.

## Each release

1. Make sure `main` is green, including native targets: `./gradlew check`.
2. Run `python3 tools/mutation_test.py` (expect 100%) and `./gradlew :benchmarks:run`, and update `docs/BENCHMARKS.md` if numbers moved.
3. Run `./gradlew apiCheck`. For intentional API changes, run `apiDump` and note them in the changelog.
4. Set `VERSION_NAME` in `gradle.properties` (drop `-SNAPSHOT`) and move `Unreleased` in `CHANGELOG.md` under the new version.
5. Dry run: `./gradlew publishToMavenLocal`, then build a sample project against `mavenLocal()`.
6. With the owner's approval: `./gradlew publishToMavenCentral --no-configuration-cache`, then release the deployment in the Central Portal UI.
7. Tag `vX.Y.Z`, push the tag, create the GitHub release with the changelog section.
8. Bump to the next `-SNAPSHOT`.

## API docs

`./gradlew :dokkaGenerate` writes the combined API reference to `build/dokka/html`. Publish it to
GitHub Pages from the release tag (only with the owner's approval).
