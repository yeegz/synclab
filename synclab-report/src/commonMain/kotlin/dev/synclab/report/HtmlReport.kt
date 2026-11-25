package dev.synclab.report

import dev.synclab.FailureReport

/**
 * Renders a failure as one self-contained HTML file: no network access, no external assets.
 * Events are laid out in one lane per node; selecting a message highlights its whole journey.
 * The page builds its DOM with `textContent` only, so trace content can never inject markup.
 */
public object HtmlReport {
    public fun render(report: FailureReport, testName: String? = report.testName): String {
        val data = SyncLabJson.encode(report).replace("</", "<\\/").replace("<!--", "<\\!--")
        val title = "SyncLab: " + (testName?.substringAfterLast('.') ?: report.violation.name)
        return TEMPLATE
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
<title>{{TITLE}}</title>
<style>
:root {
  --bg: #f7f7f5; --panel: #ffffff; --ink: #1c1d1f; --muted: #5d6066; --line: #e3e3df;
  --accent: #2357d9; --fault: #8a4f00; --fault-bg: #fff4e0; --bad: #b3261e; --bad-bg: #fdeceb;
  --hl: #e6eeff; --chip: #f0f0ec;
}
@media (prefers-color-scheme: dark) {
  :root { --bg: #0e0f12; --panel: #16181c; --ink: #ececea; --muted: #a0a3a9; --line: #2a2d33;
    --accent: #8fb0ff; --fault: #f0b45a; --fault-bg: #2b2112; --bad: #ff8a80; --bad-bg: #2e1715; --hl: #1d2740; --chip: #20232a; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink); font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
main { max-width: 1280px; margin: 0 auto; padding: 24px 16px 64px; }
h1 { font-size: 22px; margin: 0 0 4px; }
h2 { font-size: 17px; margin: 0 0 8px; }
.sub { color: var(--muted); margin: 0 0 20px; overflow-wrap: anywhere; }
.card { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 16px 18px; margin-bottom: 16px; }
.violation { border-color: var(--bad); }
.violation h2 { color: var(--bad); }
pre.msg { white-space: pre-wrap; margin: 0; font: inherit; overflow-wrap: anywhere; }
.facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; }
.fact span { display: block; color: var(--muted); font-size: 13px; }
.fact b { font-weight: 600; font-variant-numeric: tabular-nums; }
.token { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 14px; }
code { background: var(--chip); padding: 2px 6px; border-radius: 6px; font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 13px; overflow-wrap: anywhere; }
button, select, input { font: inherit; color: inherit; background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 6px 10px; }
button { cursor: pointer; }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.controls { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; margin-bottom: 10px; }
.controls label { display: flex; gap: 6px; align-items: center; color: var(--muted); font-size: 14px; }
.scroll { overflow-x: auto; max-height: 75vh; overflow-y: auto; border: 1px solid var(--line); border-radius: 8px; }
table { border-collapse: collapse; width: 100%; font-size: 13.5px; }
th { position: sticky; top: 0; background: var(--panel); text-align: left; font-weight: 600; padding: 6px 8px; border-bottom: 1px solid var(--line); white-space: nowrap; z-index: 1; }
td { padding: 4px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
td.t { color: var(--muted); font-variant-numeric: tabular-nums; white-space: nowrap; text-align: right; }
td.lane { min-width: 150px; max-width: 320px; }
.k { font-weight: 600; }
.d { display: block; color: var(--muted); overflow-wrap: anywhere; }
tr.fault td.on { background: var(--fault-bg); }
tr.fault .k { color: var(--fault); }
tr.bad td { background: var(--bad-bg); }
tr.bad .k { color: var(--bad); }
tr.hl td { background: var(--hl); }
.msgid { color: var(--accent); text-decoration: underline; background: none; border: 0; padding: 0 2px; font-size: inherit; }
.legend { color: var(--muted); font-size: 13px; margin: 8px 0 0; }
.sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
</style>
</head>
<body>
<main>
  <h1 id="title"></h1>
  <p class="sub">{{TEST}}</p>
  <section class="card violation" aria-labelledby="vh"><h2 id="vh"></h2><pre class="msg" id="vmsg"></pre></section>
  <section class="card" aria-label="Run facts">
    <div class="facts" id="facts"></div>
    <div class="token"><span>Replay token</span> <code id="token"></code>
      <button type="button" id="copy">Copy replay command</button><span id="copied" role="status" class="sr"></span></div>
  </section>
  <section class="card" aria-labelledby="th">
    <h2 id="th">Timeline</h2>
    <div class="controls">
      <label>Search <input id="q" type="search" placeholder="text, node or #id"></label>
      <label>Show <select id="filter">
        <option value="all">All events</option><option value="faults">Faults and violation</option>
        <option value="ops">Operations</option><option value="net">Network</option></select></label>
      <span id="count" class="legend" aria-live="polite"></span>
    </div>
    <div class="scroll"><table id="tl"><thead></thead><tbody></tbody></table></div>
    <p class="legend">Shaded rows are injected faults; the red row is where the problem was detected. Select a message number to follow that message.</p>
  </section>
</main>
<script type="application/json" id="data">{{DATA}}</script>
<script>
(function () {
  var r = JSON.parse(document.getElementById('data').textContent);
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = String(text);
    return e;
  }
  var titles = { INVARIANT: 'Invariant broken', NOT_CONVERGED: 'Did not converge', ASSERTION: 'Assertion failed', UNCAUGHT: 'Uncaught exception',
    STUCK: 'Scenario stuck', BUDGET: 'Budget exceeded', NONDETERMINISM: 'Nondeterministic code under test', ESCAPED_DISPATCHER: 'Work escaped the simulation' };
  function ms(t) { return t < 10000 ? t + ' ms' : (t / 1000).toFixed(2) + ' s'; }

  document.getElementById('title').textContent = titles[r.kind] || r.kind;
  document.getElementById('vh').textContent = r.name + (r.node ? ' (on ' + r.node + ')' : '') + ' at ' + ms(r.atMillis);
  document.getElementById('vmsg').textContent = r.message;

  var facts = document.getElementById('facts');
  [['Seed', r.seed],
   [r.minimised ? 'Minimal fault types' : 'Fault types seen', r.faultsInvolved.length ? r.faultsInvolved.join(', ') : 'none'],
   ['Virtual time', ms(r.virtualMillis)],
   ['Events', r.events + (r.droppedEvents ? ' (' + r.droppedEvents + ' older not kept)' : '')],
   ['Messages', r.messages], ['Steps', r.steps]].forEach(function (f) {
    var d = el('div', 'fact'); d.appendChild(el('span', null, f[0])); d.appendChild(el('b', null, f[1])); facts.appendChild(d);
  });

  var cmd = 'SYNCLAB_REPLAY=' + r.replayToken + ' ./gradlew test';
  document.getElementById('token').textContent = r.replayToken;
  document.getElementById('copy').addEventListener('click', function () {
    var done = function () { document.getElementById('copied').textContent = 'Copied'; document.getElementById('copy').textContent = 'Copied'; };
    if (navigator.clipboard) navigator.clipboard.writeText(cmd).then(done, function () { window.prompt('Copy this command', cmd); });
    else window.prompt('Copy this command', cmd);
  });

  var lanes = ['sim'];
  r.trace.forEach(function (e) { var n = e.node || 'sim'; if (lanes.indexOf(n) < 0) lanes.push(n); });
  var hr = el('tr');
  hr.appendChild(el('th', null, 'Time'));
  lanes.forEach(function (l) { var th = el('th', null, l); th.scope = 'col'; hr.appendChild(th); });
  document.querySelector('#tl thead').appendChild(hr);

  var outKinds = ['SEND', 'DUPLICATE', 'FAIL', 'UNREACHABLE', 'TIMEOUT'];
  var netKinds = ['SEND', 'DELIVER', 'REPLY', 'DROP', 'DUPLICATE', 'FAIL', 'UNREACHABLE', 'TIMEOUT'];
  var opKinds = ['OP_START', 'OP_END', 'OP_FAILED', 'NOTE', 'SETTLE', 'VIOLATION'];
  var tbody = document.querySelector('#tl tbody');

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
        td.appendChild(el('span', 'k', e.kind.toLowerCase().replace('_', ' ')));
        if (e.peer) td.appendChild(document.createTextNode((outKinds.indexOf(e.kind) >= 0 ? ' → ' : ' ← ') + e.peer));
        if (e.msg != null) {
          var b = el('button', 'msgid', '#' + e.msg); b.type = 'button'; b.dataset.msg = String(e.msg);
          b.setAttribute('aria-label', 'Highlight message ' + e.msg);
          td.appendChild(document.createTextNode(' ')); td.appendChild(b);
        }
        if (e.fault) td.appendChild(el('span', 'k', ' [' + e.fault + ']'));
        td.appendChild(el('span', 'd', e.detail));
      }
      tr.appendChild(td);
    });
    return tr;
  }

  function render() {
    var q = document.getElementById('q').value.trim().toLowerCase();
    var f = document.getElementById('filter').value;
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);
    var shown = 0;
    r.trace.forEach(function (e) {
      if (f === 'faults' && !e.fault && e.kind !== 'VIOLATION') return;
      if (f === 'ops' && opKinds.indexOf(e.kind) < 0) return;
      if (f === 'net' && netKinds.indexOf(e.kind) < 0) return;
      var text = [e.kind, e.node, e.peer, e.detail, e.fault, e.msg != null ? '#' + e.msg : ''].join(' ').toLowerCase();
      if (q && text.indexOf(q) < 0) return;
      shown++;
      tbody.appendChild(row(e));
    });
    document.getElementById('count').textContent = shown + ' of ' + r.trace.length + ' events';
  }
  tbody.addEventListener('click', function (ev) {
    var b = ev.target.closest('.msgid'); if (!b) return;
    var id = b.dataset.msg;
    tbody.querySelectorAll('tr').forEach(function (tr) { tr.classList.toggle('hl', tr.dataset.msg === id); });
  });
  document.getElementById('q').addEventListener('input', render);
  document.getElementById('filter').addEventListener('change', render);
  render();
  var v = tbody.querySelector('tr.bad'); if (v && v.scrollIntoView) v.scrollIntoView({ block: 'center' });
})();
</script>
</body>
</html>
""".trimIndent()
}
