# Notes for agents and maintainers

- Start with docs/HANDOFF.md (status, decisions, how to resume) and docs/ARCHITECTURE.md.
- Any visual change (report viewer, docs site, README hero, logo, social image, terminal output) must follow
  docs/DESIGN-LAW.md and docs/DESIGN.md, and pass DESIGN-LAW section 3 (screenshots at phone and desktop,
  light and dark) before it is reported.
- Determinism is the product: no Random.Default, wall clocks or hash-order iteration in the engine.
- Fast loop: `./gradlew check -Psynclab.native=false`. Guarantees: `python3 tools/mutation_test.py`.
- Never publish (Maven Central, GitHub, posts) without the owner's explicit approval.
