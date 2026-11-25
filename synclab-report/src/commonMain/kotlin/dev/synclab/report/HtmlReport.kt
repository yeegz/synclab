package dev.synclab.report

import dev.synclab.FailureReport

/**
 * Renders a failure as one self-contained HTML file: no network access, no external assets.
 *
 * Design concept, "relay lanes": every node is a lane and every message is a relay between lanes.
 * Selecting a message draws its journey (send, deliver, reply, or where it was lost) across the lanes.
 * The story of the failure (injected faults leading to the violation) sits above the full timeline.
 *
 * The page builds its DOM with `textContent` only, so trace content can never inject markup.
 */
public object HtmlReport {
    public fun render(report: FailureReport, testName: String? = report.testName): String {
        val data = SyncLabJson.encode(report).replace("</", "<\\/").replace("<!--", "<\\!--")
        val title = "SyncLab: " + (testName?.substringAfterLast('.') ?: report.violation.name)
        return TEMPLATE
            .replace("{{FONT_SANS}}", ReportFonts.sans)
            .replace("{{FONT_MONO}}", ReportFonts.mono)
            .replace("{{TITLE}}", escape(title))
            .replace("{{TEST}}", escape(testName ?: "simulation"))
            .replace("{{DATA}}", data)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val TEMPLATE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>{{TITLE}}</title>
<style>
@font-face { font-family: "Atkinson Next"; src: url(data:font/woff2;base64,{{FONT_SANS}}) format("woff2"); font-weight: 200 800; font-display: block; }
@font-face { font-family: "Atkinson Mono"; src: url(data:font/woff2;base64,{{FONT_MONO}}) format("woff2"); font-weight: 200 800; font-display: block; }
:root {
  --bg: #f5f6f8; --surface: #ffffff; --surface-2: #f9fafb; --ink: #15171a; --ink-2: #4d535b;
  --line: rgba(21, 23, 26, .09); --shade: rgba(21, 23, 26, .045);
  --jade: #0b7a5e; --jade-wash: rgba(11, 122, 94, .10); --jade-line: rgba(11, 122, 94, .55);
  --amber: #9a5a00; --amber-wash: rgba(214, 140, 18, .14);
  --coral: #c2362b; --coral-wash: rgba(226, 84, 68, .13); --coral-glow: rgba(226, 84, 68, .20);
  --shadow: 0 1px 2px rgba(15, 20, 25, .05), 0 10px 30px -12px rgba(15, 20, 25, .16);
  --lift: inset 0 1px 0 rgba(255, 255, 255, .8);
  --radius: 18px; --ease: cubic-bezier(.2, .8, .2, 1);
  font-size: clamp(15px, .9rem + .15vw, 17px);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0b0c0f; --surface: #121517; --surface-2: #171b1d; --ink: #edf0ee; --ink-2: #a3aba7;
    --line: rgba(237, 240, 238, .08); --shade: rgba(237, 240, 238, .04);
    --jade: #43d3a6; --jade-wash: rgba(67, 211, 166, .12); --jade-line: rgba(67, 211, 166, .65);
    --amber: #f2b65a; --amber-wash: rgba(242, 182, 90, .12);
    --coral: #ff8b7b; --coral-wash: rgba(255, 139, 123, .10); --coral-glow: rgba(255, 110, 90, .16);
    --shadow: 0 1px 0 rgba(255, 255, 255, .04) inset, 0 18px 40px -18px rgba(0, 0, 0, .8);
    --lift: inset 0 1px 0 rgba(255, 255, 255, .06);
  }
}
* { box-sizing: border-box; }
html { background: var(--bg); }
body { margin: 0; color: var(--ink); font-family: "Atkinson Next", system-ui, sans-serif; line-height: 1.5;
  background:
    radial-gradient(1100px 520px at 12% -8%, var(--coral-glow), transparent 62%),
    radial-gradient(900px 480px at 100% 0%, var(--jade-wash), transparent 60%),
    var(--bg);
  background-repeat: no-repeat; min-height: 100vh; }
main { max-width: 1240px; margin: 0 auto; padding: 2.2rem 1rem 4rem; }
code, .mono { font-family: "Atkinson Mono", ui-monospace, monospace; }

/* Mark: two lanes and a relay arc, drawn inline. */
.brand { display: flex; align-items: center; gap: .55rem; color: var(--ink-2); font-weight: 600; margin-bottom: 1.4rem; }
.brand svg { width: 28px; height: 28px; }

.hero { position: relative; background: linear-gradient(180deg, var(--surface), var(--surface-2)); border-radius: var(--radius);
  box-shadow: var(--shadow), var(--lift); padding: 1.6rem 1.6rem 1.4rem; overflow: hidden; }
.hero::before { content: ""; position: absolute; inset: 0; background: radial-gradient(600px 220px at 0% 0%, var(--coral-wash), transparent 70%); pointer-events: none; }
.hero > * { position: relative; }
.kind { color: var(--coral); font-weight: 700; margin: 0 0 .25rem; }
h1 { font-size: clamp(1.45rem, 1.1rem + 1.4vw, 2.05rem); line-height: 1.2; margin: 0 0 .4rem; letter-spacing: -.01em; overflow-wrap: anywhere; }
.test { color: var(--ink-2); margin: 0 0 1.1rem; overflow-wrap: anywhere; }
.message { white-space: pre-wrap; margin: 0 0 1.2rem; font: inherit; overflow-wrap: anywhere; background: var(--coral-wash);
  border-radius: 12px; padding: .85rem 1rem; }
.summary { margin: 0 0 1.2rem; color: var(--ink); }
.summary b { font-weight: 700; }
.replay { display: flex; flex-wrap: wrap; gap: .6rem; align-items: center; }
.replay code { flex: 1 1 260px; background: var(--shade); border-radius: 10px; padding: .7rem .85rem; font-size: .92rem; overflow-wrap: anywhere; }

.btn { font: inherit; font-weight: 600; color: var(--surface); background: var(--jade); border: 0; border-radius: 12px; min-height: 46px; padding: 0 1.1rem;
  cursor: pointer; transition: transform .16s var(--ease), background-color .2s var(--ease), box-shadow .2s var(--ease);
  box-shadow: 0 1px 0 rgba(255, 255, 255, .25) inset, 0 6px 16px -8px var(--jade-line); }
.btn:hover { background: color-mix(in srgb, var(--jade) 88%, var(--ink)); }
.btn:active { transform: scale(.97); }
:focus-visible { outline: 2.5px solid var(--jade); outline-offset: 2px; border-radius: 10px; }

section.panel { background: var(--surface); border-radius: var(--radius); box-shadow: var(--shadow), var(--lift); padding: 1.3rem 1.3rem 1.1rem; margin-top: 1.1rem; }
h2 { font-size: 1.15rem; margin: 0 0 .8rem; }

.story { list-style: none; margin: 0; padding: 0; }
.story li { display: grid; grid-template-columns: 5.5rem 1fr; gap: .8rem; padding: .55rem 0; }
.story li + li { border-top: 1px solid var(--line); margin-left: 6.3rem; padding-left: 0; }
.story li + li { margin-left: 0; }
.story .t { color: var(--ink-2); text-align: right; }
.story .what b { font-weight: 700; }
.story .fault b { color: var(--amber); }
.story .bad b { color: var(--coral); }
.story button.link { font: inherit; color: var(--jade); background: none; border: 0; padding: 0 .15rem; cursor: pointer; text-decoration: underline; text-underline-offset: 2px; }

.controls { display: grid; grid-template-columns: minmax(220px, 1fr) auto; gap: .9rem; align-items: end; margin-bottom: .9rem; }
.field label { display: block; font-weight: 600; font-size: .9rem; margin-bottom: .3rem; }
.field input { width: 100%; font: inherit; color: var(--ink); background: var(--shade); border: 1.5px solid transparent; border-radius: 12px; min-height: 46px; padding: 0 .9rem;
  transition: border-color .2s var(--ease), background-color .2s var(--ease); }
.field input:focus { border-color: var(--jade); background: var(--surface); outline: none; }
.tabs { display: grid; grid-template-columns: repeat(4, 1fr); background: var(--shade); border-radius: 13px; padding: 4px; gap: 2px; min-width: 360px; }
.tabs button { font: inherit; font-weight: 600; color: var(--ink-2); background: none; border: 0; border-radius: 10px; min-height: 40px; padding: 0 .7rem; cursor: pointer;
  transition: background-color .2s var(--ease), color .2s var(--ease), transform .16s var(--ease); }
.tabs button:hover { color: var(--ink); }
.tabs button:active { transform: scale(.97); }
.tabs button[aria-selected="true"] { background: var(--surface); color: var(--jade); box-shadow: 0 1px 3px rgba(0, 0, 0, .08); }
.count { color: var(--ink-2); font-size: .9rem; margin: 0 0 .5rem; }

.lanes { position: relative; overflow: auto; max-height: 72vh; border-radius: 12px; background: var(--surface-2); }
table { border-collapse: separate; border-spacing: 0; width: 100%; font-size: .9rem; }
th { position: sticky; top: 0; z-index: 3; text-align: left; font-weight: 700; padding: .65rem .7rem;
  background: var(--surface-2); border-bottom: 1px solid var(--line); white-space: nowrap; }
td { padding: .45rem .7rem; vertical-align: top; }
tbody tr + tr td { box-shadow: inset 0 1px 0 var(--line); }
td.t { color: var(--ink-2); white-space: nowrap; text-align: right; width: 5.5rem; }
td.lane { min-width: 150px; max-width: 300px; position: relative; z-index: 1; }
.ev { display: inline-block; border-radius: 10px; padding: .2rem .45rem; margin: -.2rem -.45rem; }
.k { font-weight: 700; }
.d { display: block; color: var(--ink-2); overflow-wrap: anywhere; }
.who { display: none; font-weight: 700; color: var(--jade); }
tr.fault td.on .ev { background: var(--amber-wash); }
tr.fault .k, tr.fault .tag { color: var(--amber); }
tr.bad td.on .ev { background: var(--coral-wash); }
tr.bad .k { color: var(--coral); }
tr.sel td.on .ev { background: linear-gradient(var(--jade-wash), var(--jade-wash)), var(--surface); box-shadow: 0 0 0 1.5px var(--jade-line), 0 6px 14px -10px var(--jade-line); }
.msg { font: inherit; color: var(--jade); background: none; border: 0; padding: 0 .1rem; cursor: pointer; text-decoration: underline; text-underline-offset: 2px; }
svg.relay { position: absolute; left: 0; top: 0; pointer-events: none; z-index: 0; overflow: visible; }
svg.relay path { fill: none; stroke: var(--jade); stroke-width: 2.2; stroke-linecap: round; opacity: .75; }
svg.relay circle { fill: var(--jade); }
svg.relay .drawn { stroke-dasharray: var(--len); stroke-dashoffset: var(--len); animation: draw .7s var(--ease) forwards; }
@keyframes draw { to { stroke-dashoffset: 0; } }
@media (prefers-reduced-motion: reduce) {
  * { transition: none !important; }
  svg.relay .drawn { animation: none; stroke-dashoffset: 0; }
}

@media (max-width: 720px) {
  main { padding-top: 1.4rem; }
  .hero { padding: 1.2rem 1.1rem; }
  .controls { grid-template-columns: 1fr; }
  .tabs { min-width: 0; }
  .story li { grid-template-columns: 4.2rem 1fr; }
  thead { display: none; }
  .lanes { max-height: none; }
  table, tbody, tr { display: block; }
  tbody tr { display: grid; grid-template-columns: 4.8rem 1fr; column-gap: .5rem; }
  td.lane:not(.on) { display: none; }
  td.lane { min-width: 0; max-width: none; }
  .who { display: inline; }
  svg.relay { display: none; }
}
</style>
</head>
<body>
<main>
  <div class="brand" aria-hidden="true">
    <svg viewBox="0 0 32 32"><rect x="3" y="4" width="4" height="24" rx="2" fill="currentColor" opacity=".35"/><rect x="25" y="4" width="4" height="24" rx="2" fill="currentColor" opacity=".35"/>
      <path d="M7 10 C 16 10, 16 22, 25 22" style="stroke: var(--jade)" stroke-width="3" fill="none" stroke-linecap="round"/><circle cx="25" cy="22" r="3" style="fill: var(--jade)"/></svg>
    SyncLab
  </div>
  <header class="hero">
    <p class="kind" id="kind"></p>
    <h1 id="title"></h1>
    <p class="test">{{TEST}}</p>
    <pre class="message" id="message"></pre>
    <p class="summary" id="summary"></p>
    <div class="replay"><code id="token"></code><button type="button" class="btn" id="copy">Copy replay command</button></div>
    <p id="copied" role="status" style="position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)"></p>
  </header>

  <section class="panel" aria-labelledby="story-h">
    <h2 id="story-h">What happened</h2>
    <ol class="story" id="story"></ol>
  </section>

  <section class="panel" aria-labelledby="tl-h">
    <h2 id="tl-h">Timeline</h2>
    <div class="controls">
      <div class="field"><label for="q">Search</label><input id="q" type="search" autocomplete="off" placeholder="Node, text or #message"></div>
      <div class="tabs" role="tablist" aria-label="Events to show">
        <button role="tab" aria-selected="true" data-f="all">All</button>
        <button role="tab" aria-selected="false" data-f="faults">Faults</button>
        <button role="tab" aria-selected="false" data-f="ops">Actions</button>
        <button role="tab" aria-selected="false" data-f="net">Network</button>
      </div>
    </div>
    <p class="count" id="count" aria-live="polite"></p>
    <div class="lanes" id="lanes"><table id="tl"><thead></thead><tbody></tbody></table></div>
  </section>
</main>
<script type="application/json" id="data">{{DATA}}</script>
<script>
(function () {
  var r = JSON.parse(document.getElementById('data').textContent);
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = String(text); return e; }
  function ms(t) { return t < 10000 ? t + ' ms' : (t / 1000).toFixed(2) + ' s'; }
  var kinds = { INVARIANT: 'Invariant broken', NOT_CONVERGED: 'Did not converge', ASSERTION: 'Assertion failed', UNCAUGHT: 'Uncaught exception',
    STUCK: 'Scenario stuck', BUDGET: 'Budget exceeded', NONDETERMINISM: 'Nondeterministic code under test', ESCAPED_DISPATCHER: 'Work escaped the simulation' };
  var verbs = { SEND: 'sent', DELIVER: 'received', REPLY: 'got reply', DROP: 'lost', DUPLICATE: 'duplicated', FAIL: 'failed fast', UNREACHABLE: 'unreachable',
    TIMEOUT: 'timed out', HANDLER_ERROR: 'handler threw', DISCONNECT: 'went offline', RECONNECT: 'back online', PARTITION: 'partitioned', HEAL: 'healed',
    CRASH: 'crashed', RESTART: 'restarted', CLOCK: 'clock', OP_START: 'started', OP_END: 'finished', OP_FAILED: 'failed', NOTE: 'note',
    SUPPRESSED: 'fault skipped', SETTLE: 'settling', VIOLATION: 'problem detected' };
  var outKinds = ['SEND', 'DROP', 'DUPLICATE', 'FAIL', 'UNREACHABLE', 'TIMEOUT'];

  document.getElementById('kind').textContent = kinds[r.kind] || r.kind;
  document.getElementById('title').textContent = r.name;
  document.getElementById('message').textContent = r.message;
  var s = document.getElementById('summary');
  s.appendChild(document.createTextNode('Seed '));
  s.appendChild(el('b', null, r.seed));
  s.appendChild(document.createTextNode(r.faultsInvolved.length
    ? (r.minimised ? '. Reproduces with only ' : '. Faults active: ') : '. Fails with no faults at all'));
  if (r.faultsInvolved.length) s.appendChild(el('b', null, r.faultsInvolved.join(', ')));
  s.appendChild(document.createTextNode('. Detected at ' + ms(r.atMillis) + (r.node ? ' on ' + r.node : '') + ', after ' + r.events + ' events.'));

  var cmd = 'SYNCLAB_REPLAY=' + r.replayToken + ' ./gradlew test';
  document.getElementById('token').textContent = cmd;
  document.getElementById('copy').addEventListener('click', function () {
    var b = this;
    var done = function () { b.textContent = 'Copied'; document.getElementById('copied').textContent = 'Replay command copied';
      setTimeout(function () { b.textContent = 'Copy replay command'; }, 1800); };
    if (navigator.clipboard) navigator.clipboard.writeText(cmd).then(done, function () { window.prompt('Copy this command', cmd); });
    else window.prompt('Copy this command', cmd);
  });

  // ---- Story: injected faults, then the violation -----------------------------------------------
  var story = document.getElementById('story');
  var key = r.trace.filter(function (e) { return (e.fault && e.kind !== 'SUPPRESSED') || e.kind === 'VIOLATION'; });
  if (key.length > 14) key = key.slice(0, 6).concat([null]).concat(key.slice(-7));
  key.forEach(function (e) {
    var li = el('li');
    if (!e) { li.appendChild(el('span', 't', '')); li.appendChild(el('span', 'what', '…')); story.appendChild(li); return; }
    li.appendChild(el('span', 't', ms(e.t)));
    var w = el('span', 'what ' + (e.kind === 'VIOLATION' ? 'bad' : 'fault'));
    if (e.kind === 'VIOLATION') {
      w.appendChild(el('b', null, 'Problem detected'));
      w.appendChild(document.createTextNode(': ' + r.name));
    } else {
      var who = (e.node || 'Simulation') + (e.peer ? (outKinds.indexOf(e.kind) >= 0 ? ' \u2192 ' : ' \u2190 ') + e.peer : '');
      w.appendChild(el('b', null, who + ' ' + (verbs[e.kind] || e.kind.toLowerCase())));
      w.appendChild(document.createTextNode(': ' + e.detail));
    }
    if (e.msg != null) {
      var b = el('button', 'link', 'follow #' + e.msg); b.type = 'button';
      b.addEventListener('click', function () { select(String(e.msg), true); });
      w.appendChild(document.createTextNode(' ')); w.appendChild(b);
    }
    li.appendChild(w); story.appendChild(li);
  });
  if (!key.length) story.appendChild(el('li', null, 'No faults were injected. This happens on a perfect network too.'));

  // ---- Timeline ---------------------------------------------------------------------------------
  var lanes = ['sim'];
  r.trace.forEach(function (e) { var n = e.node || 'sim'; if (lanes.indexOf(n) < 0) lanes.push(n); });
  if (!r.trace.some(function (e) { return !e.node; })) lanes.shift();
  var hr = el('tr'); hr.appendChild(el('th', null, 'Time'));
  lanes.forEach(function (l) { var th = el('th', null, l === 'sim' ? 'Simulation' : l); th.scope = 'col'; hr.appendChild(th); });
  document.querySelector('#tl thead').appendChild(hr);
  var netKinds = ['SEND', 'DELIVER', 'REPLY', 'DROP', 'DUPLICATE', 'FAIL', 'UNREACHABLE', 'TIMEOUT'];
  var opKinds = ['OP_START', 'OP_END', 'OP_FAILED', 'NOTE', 'SETTLE', 'VIOLATION'];
  var tbody = document.querySelector('#tl tbody');
  var lanesBox = document.getElementById('lanes');
  var filter = 'all', selected = null;

  function row(e) {
    var tr = el('tr');
    if (e.fault && e.kind !== 'SUPPRESSED') tr.classList.add('fault');
    if (e.kind === 'VIOLATION') tr.classList.add('bad');
    tr.dataset.msg = e.msg == null ? '' : String(e.msg);
    tr.appendChild(el('td', 't', ms(e.t)));
    var lane = e.node || 'sim';
    lanes.forEach(function (l) {
      var td = el('td', 'lane');
      if (l === lane) {
        td.classList.add('on');
        var ev = el('span', 'ev');
        ev.appendChild(el('span', 'who', (l === 'sim' ? 'Simulation' : l) + ' '));
        ev.appendChild(el('span', 'k', verbs[e.kind] || e.kind.toLowerCase()));
        if (e.peer) ev.appendChild(document.createTextNode((outKinds.indexOf(e.kind) >= 0 ? ' → ' : ' ← ') + e.peer));
        if (e.msg != null) {
          var b = el('button', 'msg', '#' + e.msg); b.type = 'button'; b.dataset.msg = String(e.msg);
          b.setAttribute('aria-label', 'Follow message ' + e.msg);
          ev.appendChild(document.createTextNode(' ')); ev.appendChild(b);
        }
        if (e.fault) ev.appendChild(el('span', 'tag', ' ' + e.fault.toLowerCase().replace('_', ' ')));
        ev.appendChild(el('span', 'd', e.detail));
        td.appendChild(ev);
      }
      tr.appendChild(td);
    });
    return tr;
  }

  function render() {
    var q = document.getElementById('q').value.trim().toLowerCase();
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);
    var shown = 0;
    r.trace.forEach(function (e) {
      if (filter === 'faults' && !e.fault && e.kind !== 'VIOLATION') return;
      if (filter === 'ops' && opKinds.indexOf(e.kind) < 0) return;
      if (filter === 'net' && netKinds.indexOf(e.kind) < 0) return;
      var text = [e.kind, verbs[e.kind], e.node, e.peer, e.detail, e.fault, e.msg != null ? '#' + e.msg : ''].join(' ').toLowerCase();
      if (q && text.indexOf(q) < 0) return;
      shown++; tbody.appendChild(row(e));
    });
    document.getElementById('count').textContent = shown === r.trace.length ? r.trace.length + ' events' : shown + ' of ' + r.trace.length + ' events';
    drawRelay(false);
  }

  // Draws the selected message's journey across lanes (SVG behind the cells).
  var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'relay'); svg.setAttribute('aria-hidden', 'true');
  lanesBox.appendChild(svg);
  function drawRelay(animate) {
    while (svg.firstChild) svg.removeChild(svg.firstChild);
    tbody.querySelectorAll('tr').forEach(function (tr) { tr.classList.toggle('sel', selected !== null && tr.dataset.msg === selected); });
    if (selected === null) return;
    var box = lanesBox.getBoundingClientRect();
    var rects = [];
    tbody.querySelectorAll('tr.sel td.on .ev').forEach(function (ev) {
      var b = ev.getBoundingClientRect(), ox = lanesBox.scrollLeft - box.left, oy = lanesBox.scrollTop - box.top;
      rects.push({ l: b.left + ox, r: b.right + ox, y: b.top + oy + Math.min(14, b.height / 2), cx: (b.left + b.right) / 2 + ox });
    });
    svg.setAttribute('width', lanesBox.scrollWidth); svg.setAttribute('height', lanesBox.scrollHeight);
    if (rects.length < 2) return;
    var pts = [], d = '';
    for (var i = 1; i < rects.length; i++) {
      var a = rects[i - 1], b2 = rects[i], sx, ex;
      if (Math.abs(a.cx - b2.cx) < 4) { sx = a.l - 6; ex = b2.l - 6; }       // same lane: hug the left edge
      else if (b2.cx > a.cx) { sx = a.r; ex = b2.l; }                         // to the right
      else { sx = a.l; ex = b2.r; }                                           // to the left
      var mx = (sx + ex) / 2;
      d += (d ? ' M' : 'M') + sx + ' ' + a.y + ' C ' + (Math.abs(sx - ex) < 4 ? sx - 28 : mx) + ' ' + a.y + ', ' +
        (Math.abs(sx - ex) < 4 ? ex - 28 : mx) + ' ' + b2.y + ', ' + ex + ' ' + b2.y;
      pts.push([sx, a.y], [ex, b2.y]);
    }
    var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    p.setAttribute('d', d); svg.appendChild(p);
    var len = p.getTotalLength();
    p.style.setProperty('--len', len);
    if (animate) p.setAttribute('class', 'drawn');
    pts.forEach(function (pt) {
      var c = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      c.setAttribute('cx', pt[0]); c.setAttribute('cy', pt[1]); c.setAttribute('r', 3.5); svg.appendChild(c);
    });
  }
  function select(id, scroll) {
    selected = selected === id && !scroll ? null : id;
    if (scroll && filter !== 'all') { setFilter('all'); }
    drawRelay(true);
    if (scroll) { var first = tbody.querySelector('tr.sel'); if (first && first.scrollIntoView) first.scrollIntoView({ block: 'center' }); setTimeout(function () { drawRelay(false); }, 400); }
  }
  function setFilter(f) {
    filter = f;
    document.querySelectorAll('.tabs button').forEach(function (b) { b.setAttribute('aria-selected', String(b.dataset.f === f)); });
    render();
  }
  tbody.addEventListener('click', function (ev) { var b = ev.target.closest('.msg'); if (b) select(b.dataset.msg, false); });
  document.querySelectorAll('.tabs button').forEach(function (b) { b.addEventListener('click', function () { setFilter(b.dataset.f); }); });
  document.getElementById('q').addEventListener('input', render);
  window.addEventListener('resize', function () { drawRelay(false); });
  render();
})();
</script>
</body>
</html>
""".trimIndent()
}
