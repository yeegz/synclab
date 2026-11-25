# Design: the report viewer and terminal output

SyncLab's interface is its failure output: the terminal text and the HTML report. Both follow one concept.

## Concept: relay lanes

Every node is a **lane**. Every message is a **relay** between lanes. A sync bug is almost always a
relay that went wrong: lost after the server applied it, delivered twice, overtaken. So the viewer's
signature element is the drawn journey of one message across the lanes: select `#26` and a jade curve
runs from the sender's box to the receiver's, then to where the reply was lost.

## Reading order (both terminal and HTML)

1. **What broke**: the violation kind and the invariant's name, written by the developer in their own domain language.
2. **The evidence**: expected vs actual, with the difference first ("only in actual: e-…-dup14").
3. **What it needs**: the minimal fault types for this seed, in one sentence.
4. **The story**: injected faults in time order, ending at the violation.
5. **The full timeline**, for digging.
6. **One line to reproduce it.**

## Identity

| Element | Choice | Why |
|---|---|---|
| Type | Atkinson Hyperlegible Next (UI), Atkinson Hyperlegible Mono (commands, ids) | Designed to keep 0/O, 1/l/I and 5/S distinct, which is what you need when reading ids, hashes and replay tokens under pressure. Embedded (OFL) so reports work offline and in CI artifacts |
| Brand colour | Jade (`#0b7a5e` light, `#43d3a6` dark) | The colour of "in sync". Used for actions, the selected relay and the mark |
| Fault colour | Amber wash | An injected fault is a cause, not yet a failure |
| Problem colour | Coral, plus a soft glow from the top-left of the page | The page is lit from the problem, so the eye lands there first |
| Surfaces | Neutral cool light (`#f5f6f8`); deep near-black dark (`#0b0c0f`) with lit, raised surfaces | No paper tones, no grey dark mode |
| Mark | Two lanes and a relay arc | The concept, at 28 px |

## Rules the viewer follows

- No status dots, eyebrow chips, stat tiles or fade-ins. Motion only answers an action (the relay
  draws once in about 0.7 s and settles). `prefers-reduced-motion` turns it off.
- Equal-width filter tabs, 46 px controls, one press scale, one focus ring (jade, 2.5 px).
- Phone layout: lanes collapse into one list where each row names its node. The relay drawing is
  hidden there, and the selected message is highlighted instead.
- Built with `textContent` only: trace content cannot inject markup.
- Self-contained: no network requests, no external assets. About 120 to 150 KB per report.

Screenshots: [relay lanes](images/report-relay-lanes.jpg), [dark](images/report-dark.jpg).
