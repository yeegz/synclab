# Yousof's design law

This file collects every design, polish and UX rule Yousof has given across his projects, as recorded in memory up to 25 Nov 2026. It applies to every project unless he says otherwise. When a project's own DESIGN.md is more specific, that file wins for that project.

---

## 0. The bar

- **Unique.** Every project gets its own identity: a named concept rooted in its domain, its own type, colour, mark, motion and one signature element. Two of his projects must never look like siblings. Match the *quality* of Photoshoot, Bupples, the portfolio and the rest, not their *look*.
- **"Fancy charm."** It should be rich, interactive and smoothly animated, with depth: layered surfaces, soft diffused shadows, real shading and light, plus glow or frost where it suits the product. Safe, sparse minimalism reads as "basic" and "dead" to him.
- **Not over the top.** That means no gimmicks, no decorative clutter and no ambient noise. It does not mean sparse.
- **Never dead.** No flat, lifeless screen backgrounds. Light every screen from its own content and colour. He says "shouldn't feel dead" often, so check every new surface for it.
- **The test before showing him:** screenshot the screen and ask two questions. Would a designer put this in their portfolio under their own name? Could someone tell it apart from a Dribbble template or AI output at a glance? If either answer is no, it isn't done.
- **The polish benchmarks:** Photoshoot (~/Documents/photoshoot), the Bupples landing's "ALIVE" energy, the portfolio's nav pill (FLIP morph, velocity-aware timing, tint hover, scale press, spring indicator, full reduced-motion handling), WayClub's Luma-level event pages, and the Hostmortem showcase.

## 1. Banned: the AI and generic tells he keeps pointing out

- **Status dots.** No coloured status dots, "● label" chips, traffic-light colours, pulsing presence dots or generic status pills. Write status as specific words in context ("Closes Fri 3 Nov", "3 of 4 roles filled").
- **Tracked caps and mono labels.** No tracked uppercase eyebrows. No monospace in the UI (mono only for code or raw data). No tabular or slashed-zero figures in body text.
- **Generic fonts.** Inter, Manrope, Roboto, Space Grotesk, Plus Jakarta Sans, DM Sans, Poppins, IBM Plex (as the UI voice), Geist, Bricolage Grotesque, Instrument and Funnel are out as the product's voice. No robotic or techy mono display faces. Pick an unmodified Google Font with character that fits the product, self-host and subset it, and be able to say why it belongs to this product. No stretched or wide type (keep width at 100%).
- **The generic AI look.** That covers purple or violet gradients on near-black; glow behind everything; glass on every surface; every surface a 24px card with an icon in a tinted circle; three-up feature cards; "Hello, name" dashboards with a stats row; uniform drop shadows; everything fading in on load; cards that lift, glow or zoom on hover; image zoom on hover; pulsing buttons; cursor-following lines; sparkle icons; coloured side-stripes on cards; dashed info boxes; eyebrow chips everywhere; stat tiles just for the sake of it.
- **Icons.** No generic thin-outline icon sets scattered everywhere. No emoji or text glyphs standing in for icons; draw them. Use real app icons, never approximations. No icon loops on CSS timers.
- **Background motion.** No floating blobs, drifting shapes, particles or idle loops. Nothing animates forever, and motion must settle (Bupples heated phones because of this). Bursts are fine, endless loops are not.
- **Landing-page tropes.** No highlighter blocks behind headline words, giant tight four-line headlines, icon eyebrows, tilted floating phone mocks, tiny grey trust rows, generic spec tables, the copy-left/mock-right hero, FAQ walls or long feature lists. No "see an example" secondary CTA next to the main button. No interactive toy widgets on landing pages ("cringe").
- **Clutter and over-explaining.** No helper sentence under every heading, no subtitle explaining the page's philosophy, no label that restates its control, no copy that repeats the headline, no popups that repeat a panel, no prototype or "concept" notices on screens. Never say anything twice.
- **Chrome.** Never two floating bars stacked. No stacked hairlines; dividers only between rows, inset to the text. No flat frosted slab with a hairline as a sticky header; fade it into the page instead. Never two entry points for the same concept (for example a You tab and an Account button).
- **Colour.** No cream, ivory or paper backgrounds (light surfaces are neutral or faintly cool, around #F5F6F8). No blue-grey palettes. No fixed cool greys on warm sheets; use a see-through shade of the background. No dark mode that's just an inverted theme or reads as dark grey; use a deep near-black (around #0B0C0F) with a tint of the brand and lit surfaces. Icon plates stay deep.
- **Never broken.** No raw errors, visible "refreshing", stuck popups or toasts, broken-image frames, flicker, lists that move under the pointer after a tap, or dead space at the bottom of a scroll.

## 2. Do: what he keeps asking for

- **Prototype first.** Show two or three rendered directions (screenshots or a live prototype) before propagating a design system. Iterate on the one he picks.
- **Custom motion** specific to the product (springs, wobble while settling, timed glides, physical touchable scenes, a branded loader drawn from the mark instead of spinners, an animated launch). Motion answers touch or real data changes. Respect reduced motion and keep content visible without JS.
- **Button feedback everywhere.** One hover fill, one press scale, one focus ring and token timings, all derived from one well-made component (see the portfolio nav pill).
- **Colour from the content.** Tint pages and cards from the project's, poster's or product's own colour (Luma-style). Use colour boldly but with meaning.
- **Depth and material.** Marks and hero objects get material at display sizes: sheen, top-edge highlight, soft shadow. Use soft diffused shadows, not hard lips. Raised soft buttons in dark mode; black buttons get a visible grey lip in light mode.
- **Friendly, plain UX.** A non-technical person should never feel stuck. Status reads as verbs and people. One idea per screen. Onboarding asks one question at a time and shows the product with custom motion (no icon slides). Prefill from context, use chips and drag instead of typing, and aim for three taps to the result. Lead with numbers and what to do next, with detail behind "More detail".
- **Navigation.** Every screen answers "where am I, what is this, what next". Back returns you to where you came from. Escape closes sheets. Presentation follows the content: one tap, then a pop-up sheet, then a full screen.
- **Controls.** Equal-width tabs with big targets, icons plus plain words and a tinted selected state. Buttons are about 48px, with one primary plus a text link, never full-width slabs. Destructive actions are a small text link plus a confirmation, coloured red. Arrows stay consistent (› opens, ‹ back, → act, ↗ leaves). Fields look premium: label above, filled box, focus ring.
- **Images are product.** Show real, related images, not generic ones. Designed placeholders. Overlays for text contrast. Alt text.
- **Responsive and accessible.** Render 320, 360/390, 768 and 1280+ in light and dark before reporting. Use 44–48px targets, keyboard focus and reduced motion, and size in rem with a fluid root.
- **Landing pages.** At most about six sections, one idea each, short captions. The hero is the real product (real output, real screens), composed imagery and type, with calm motion tied to scroll or stopping within five seconds. A proper nav and footer.
- **Developer tools** get the same care in the terminal output, error messages, README hero, docs site, social preview image and logo.
- **Finish completely.** Cover every state (loading, empty, error, retry, disabled, selected) and every surface, the admin or committee side as much as the public side. Never skip something as "already done" without proving it against the request.
- **Real products, not demos.** Don't use the word "demo" in public copy. Keep status honest. Judge by whether a real person can finish the journey in the running app.
- **International.** No hardcoded currency, timezone or locale. Format per user.
- **Hand-off.** Keep docs/HANDOFF.md (done, left, decisions, sources, how to resume) up to date in the repo.
- **Review inherited work.** Treat earlier agent work (Codex or Claude) as a draft and rework whatever is weak.
- **Costs.** Never create database or hosting costs without asking first. Use the cheapest option that works.
- **Public posting.** Only with his explicit yes, one post at a time.

## 3. Before reporting anything visual

1. Screenshot desktop, phone (390/360) and dark mode.
2. Go through section 1 line by line and remove every hit.
3. Put it side by side with a benchmark screen (Photoshoot, the portfolio, Bupples) and check depth, shading, motion, type and detail.
4. Check it is visibly different from his other projects.
5. Send him the screenshots.
