
/** One-off (17.11.2026 chat): backs up engine_bundle.js, applies the fixes, logs. Remove after running. */
function patch1711Chat() {
var PATCHES = [

// ⓪ "item:LOG:3:15040" was read as item LOG × 3 (ids with a colon: Jungle/Spruce/Birch wood, Cocoa, Lapis…).
//    Now the LAST part is the count and everything between is the id.
['parse-cost-colon-ids',
`if (p[0] === 'item') return { kind: 'item', id: p[1], n: Number(p[2] || 1) };`,
`if (p[0] === 'item') return p.length > 3 ? { kind: 'item', id: p.slice(1, -1).join(':'), n: Number(p[p.length - 1]) || 1 } : { kind: 'item', id: p[1], n: Number(p[2] || 1) };`],

// ① Claims: Hypixel never says "0 unclaimed". A later join (first chat line after a 10+ min gap)
//    without the leveling/achievement line = he claimed them.
['claims-join-rule',
`function E_claims(evs, nowMs) {
 var o = {};
 (evs || []).forEach(function (x) { if (x.kind === 'claim' && nowMs - x.t < 3 * 864e5 && (!o[x.what] || x.t > o[x.what].t)) o[x.what] = x; });
 return Object.keys(o).map(function (k) { return o[k]; }).filter(function (x) { return x.n > 0; });
}`,
`function E_claims(evs, nowMs) {
  var o = {};
  (evs || []).forEach(function (x) { if (x.kind === 'claim' && nowMs - x.t < 3 * 864e5 && (!o[x.what] || x.t > o[x.what].t)) o[x.what] = x; });
  // 17.11.2026: Hypixel never says "0 unclaimed". The leveling + achievement lines come when he joins, so a later join
  // (first chat line after a 10+ min gap) without that line = he claimed them. Bestiary nags repeat while he plays: latest wins.
  var ts = (evs || []).map(function (x) { return x.t; }).filter(function (t) { return t > 0; }).sort(function (a, b) { return a - b; }), joins = [];
  for (var i = 1; i < ts.length; i++) if (ts[i] - ts[i - 1] > 10 * 60e3) joins.push(ts[i]);
  var JOIN = { leveling: 1, achievement: 1 };
  return Object.keys(o).map(function (k) { return o[k]; }).filter(function (x) {
    if (!(x.n > 0)) return false;
    if (!JOIN[x.what]) return true;
    return !joins.some(function (j) { return j > x.t + 5 * 60e3; });
  });
}`],

// ② Minion maths: ×count for identical copies; what earlier tiers / earlier minions use is not counted twice
//    (incl. enchanted items and the items still sitting in his minions); "ready from minion contents" is flagged.
['timeline-shared-stock',
`function E_minionTimeline(p, st, gen, T, nowMs) {
 var max = (gen.minionMax || {})[p.type + '_GENERATOR'] || 11, out = [], used = {}, t0 = nowMs;
 for (var k = 1; k <= 2 && p.tier + k <= max; k++) {
 var tier = p.tier + k, rec = (gen.recipes || {})[p.type + '_GENERATOR_' + tier];
 if (!rec || rec.none) { out.push({ tier: tier, unknownRecipe: true }); break; }
 var ing = {}; Object.keys(rec.ing || {}).forEach(function (id) { if (!/_GENERATOR_\\d+$/.test(id)) ing[id] = rec.ing[id]; });
 var c = E_costFromRecipe({ ing: ing }, st, gen, null);
 var need = E_parseCost(c.cost).filter(function (x) { return x.kind === 'item'; }).map(function (x) {
 var have = Math.max(0, (st.items[x.id] || 0) - (used[x.id] || 0));
 used[x.id] = (used[x.id] || 0) + x.n;
 return { id: x.id, have: have, need: x.n };
 });
 var miss = need.filter(function (x) { return x.have < x.need; });
 var kept = miss.filter(function (x) { return (st.keep || {})[x.id] === Infinity; });
 if (kept.length) { out.push({ tier: tier, need: need, readyMs: null, kept: kept.map(function (x) { return x.id; }), lines: [], unknown: [], recipeLines: c.lines }); break; }
 var eta = miss.length ? E_etaMissing(miss, T, t0) : { ms: nowMs, days: 0, lines: [], unknown: [] };
 if (miss.length && eta.ms) t0 = eta.ms;
 out.push({ tier: tier, need: need, readyMs: miss.length ? eta.ms : nowMs, days: eta.days, lines: eta.lines, unknown: eta.unknown, recipeLines: c.lines });
 if (miss.length && !eta.ms) break;
 }
 return { max: max, steps: out };
}`,
`function E_minionTimeline(p, st, gen, T, nowMs, pool) {
  var max = (gen.minionMax || {})[p.type + '_GENERATOR'] || 11, out = [], used = {}, t0 = nowMs;
  // 17.11.2026: one stock for the whole plan. pool = what minions earlier in the list already use for their ready-now tiers
  // (pool.__pend = items still inside his minions, used up the same way); ×count = every identical copy gets upgraded.
  pool = pool || {}; if (!pool.__pend) pool.__pend = JSON.parse(JSON.stringify((T && T.pending) || {}));
  var mult = Math.max(1, Number(p.count) || 1), T2 = {}; Object.keys(T || {}).forEach(function (k) { T2[k] = T[k]; }); T2.pending = pool.__pend;
  for (var k = 1; k <= 2 && p.tier + k <= max; k++) {
    var tier = p.tier + k, rec = (gen.recipes || {})[p.type + '_GENERATOR_' + tier];
    if (!rec || rec.none) { out.push({ tier: tier, unknownRecipe: true }); break; }
    var ing = {}; Object.keys(rec.ing || {}).forEach(function (id) { if (!/_GENERATOR_\\d+$/.test(id)) ing[id] = rec.ing[id] * mult; });
    var view = { items: {}, keep: st.keep }; Object.keys(st.items || {}).forEach(function (id) { view.items[id] = Math.max(0, (st.items[id] || 0) - (used[id] || 0) - (pool[id] || 0)); });
    var c = E_costFromRecipe({ ing: ing }, view, gen, null);
    var need = E_parseCost(c.cost).filter(function (x) { return x.kind === 'item'; }).map(function (x) {
      var have = view.items[x.id] || 0;
      used[x.id] = (used[x.id] || 0) + x.n;
      return { id: x.id, have: have, need: x.n };
    });
    var miss = need.filter(function (x) { return x.have < x.need; });
    var kept = miss.filter(function (x) { return (st.keep || {})[x.id] === Infinity; });
    if (kept.length) { out.push({ tier: tier, need: need, readyMs: null, kept: kept.map(function (x) { return x.id; }), lines: [], unknown: [], recipeLines: c.lines }); break; }
    var eta = miss.length ? E_etaMissing(miss, T2, t0) : { ms: nowMs, days: 0, lines: [], unknown: [] };
    if (miss.length && eta.ms) t0 = eta.ms;
    var readyNow = !miss.length || (eta.fromMinions && eta.ms <= nowMs + 60000);
    if (readyNow) { // this tier gets crafted now: its items leave the shared stock (and the minion contents it needs)
      need.forEach(function (x) { pool[x.id] = (pool[x.id] || 0) + Math.min(x.need, x.have); used[x.id] -= Math.min(x.need, x.have); });
      miss.forEach(function (x) {
        var short = x.need - x.have, P = pool.__pend;
        if (P[x.id]) { var a = Math.min(P[x.id], short); P[x.id] -= a; short -= a; }
        var base = /^ENCHANTED_/.test(x.id) ? (E_ENCH_BASE[x.id] || x.id.replace(/^ENCHANTED_/, '')) : null;
        if (short > 0 && base && P[base]) P[base] = Math.max(0, P[base] - short * 160);
      });
    }
    out.push({ tier: tier, need: need, readyMs: miss.length ? eta.ms : nowMs, days: eta.days, lines: eta.lines, unknown: eta.unknown, recipeLines: c.lines, fromMinions: !!(miss.length && eta.fromMinions) });
    if (miss.length && !eta.ms) break;
  }
  return { max: max, steps: out };
}`],

['plan-minions-pool',
`inp.placed.forEach(function (p) {
 var tl = E_minionTimeline(p, st, gen, T, now);`,
`var pool = {}; // 17.11.2026: one shared stock across all placed minions
  inp.placed.forEach(function (p) {
  var tl = E_minionTimeline(p, st, gen, T, now, pool);`],

// subtask title says when the items are still inside his minions
['subtask-empty-flag',
`content: E_minionEmoji(r.p.type) + ' ' + E_wiki(r.name) + ' ' + E_romanOf(r.s.tier - 1) + ' → ' + E_romanOf(r.s.tier) + (r.p.count > 1 ? ' (×' + r.p.count + ' placed)' : ''),`,
`content: E_minionEmoji(r.p.type) + ' ' + E_wiki(r.name) + ' ' + E_romanOf(r.s.tier - 1) + ' → ' + E_romanOf(r.s.tier) + (r.p.count > 1 ? ' (×' + r.p.count + ' placed)' : '') + (r.s.fromMinions ? ' · 📥 empty minions first' : ''),`],

// the brief only says "sacks have it all" when that's true
['brief-sacks-text',
`' minion tier' + (craftNow.length > 1 ? 's' : '') + ' (sacks have it all)'`,
`' minion tier' + (craftNow.length > 1 ? 's' : '') + (craftNow.some(function (t) { return /📥/.test(t.content); }) ? ' (empty your minions first)' : ' (sacks have it all)')`],

// ③ Herald minion steps: craft the enchanted items first (button), empty minions first, ×copies
['guide-minion-precraft',
`steps.push({ id: t.key, title: m[1] + ' ' + m[2] + ' ' + m[3] + ' → ' + m[4], text: 'open the minion → Quick-Upgrade' + (n > 1 ? ' ×' + n : ''), cmds: i === 0 ? [E_cmdObj_('/warp home')] : [], url: url(t),
 done: { pattern: 'You have upgraded your Minion to Tier ' + m[4] + '(?![IVXLC])', count: 1 } });`,
`// 17.11.2026: the recipe note says what Supercraft has to make first ("128× Enchanted Jungle Log (34 owned + 15,040 Jungle via Supercraft)")
    var pre = [], prc = [], rxp = /(\\d[\\d,]*)× (Enchanted [^(\\n]+?) \\((?:([\\d,]+) owned \\+ )?[\\d,]+ [^)\\n]+? via Supercraft\\)/g, mp2, num = function (s) { return Number(String(s || '0').replace(/,/g, '')); };
    while ((mp2 = rxp.exec(String(t.description || ''))) !== null) { var k2 = num(mp2[1]) - num(mp2[3]); if (k2 > 0) { pre.push(E_n(k2) + ' ' + mp2[2]); prc.push({ label: 'Craft ' + mp2[2], cmd: '/recipe ' + mp2[2] }); } }
    var cnt = /\\(×(\\d+) placed\\)/.exec(E_plainTitle_(t.content)), copies = cnt ? Number(cnt[1]) : 1, fromM = /📥/.test(t.content);
    steps.push({ id: t.key, title: m[1] + ' ' + m[2] + ' ' + m[3] + ' → ' + m[4] + (copies > 1 ? ' ×' + copies : ''),
      text: (fromM ? 'empty your minions first · ' : '') + (pre.length ? 'craft ' + pre.join(' + ') + ' · ' : '') + 'then minion → Quick-Upgrade' + (n > 1 ? ' ×' + n : ''),
      cmds: (function () { var w = i === 0 ? [E_cmdObj_('/warp home')] : [], r = prc.filter(function (c) { return ok(c.cmd); }); return fromM ? w.concat(r) : r.concat(w); })(), url: url(t),
      done: { pattern: 'You have upgraded your Minion to Tier ' + m[4] + '(?![IVXLC])', count: copies } });`],

// ⑤ every Herald step carries its Todoist note as plain lines, so Herald can show it in game (no Todoist needed)
['guide-info-lines',
`return { id: 'session|' + L.start, steps: steps.slice(0, 30) };`,
`steps.forEach(function (s) { // 17.11.2026: the task note in plain lines for Herald's in-game details
    var src = out.filter(function (x) { return x.key === s.id || x.key === String(s.id).replace(/:\\d+$/, ''); })[0];
    var d = src ? String(src.description || '') : '';
    s.info = d.split('\\n').map(function (l) { return E_plainTitle_(l).replace(/\`/g, '').replace(/^-\\s+/, '· ').trim(); })
      .filter(function (l) { return l && !/^(⚙️ How it works|🧩 How)$/.test(l); }).slice(0, 10).map(function (l) { return l.slice(0, 140); });
  });
  return { id: 'session|' + L.start, steps: steps.slice(0, 30) };`],

// ④ Patch news: a new patch gets every activity re-checked, a full read of the release thread, and a 📰 task + Herald popup
['news-all-activities',
`var nActs = why === 'weekly' || why === 'first run' || /asked/.test(why) ? AI.DEEP_ACTS : 0;`,
`var nActs = why === 'weekly' || why === 'first run' || /asked|news/.test(why) ? AI.DEEP_ACTS : 0;`],

['news-prompt',
`var g = 'Global pass. Reason: ' + why + '. Current mayor/minister from the Hypixel API: '`,
`var g = 'Global pass. Reason: ' + why + '. ' + (/news/.test(why) ? '0) A NEW SKYBLOCK PATCH IS OUT. Read the FULL release thread on hypixel.net (every section, not the release candidates or alpha posts). Return "update": {"title":"<patch name>","link":"<thread url>","lines":["max 6 short lines: what changed that matters for HIM (items he owns, his goals, his money methods), each ending with what to do"]}. Also disable or fix every playbook row the patch makes wrong (removed or reworked items, price changes) and add rows for new things worth doing at his stage.\\n' : '') + 'Current mayor/minister from the Hypixel API: '`],

['news-json-shape',
`"checkin":["q1","q2","q3"]} (timing/checkin only when asked).'`,
`"checkin":["q1","q2","q3"], "update":{"title":"","link":"","lines":[]}} (timing/checkin/update only when asked).'`],

['news-collect',
`if (j.checkin && j.checkin.length) state.ai.checkin = { at: now, questions: j.checkin.slice(0, 3) };`,
`if (j.checkin && j.checkin.length) state.ai.checkin = { at: now, questions: j.checkin.slice(0, 3) };
    if (o.custom_id === 'global' && j.update && j.update.lines && j.update.lines.length) state.ai.news = { at: now, why: state.ai.batchWhy || '', title: String(j.update.title || String(state.ai.batchWhy || '').replace(/^new SkyBlock news:\\s*/, '') || 'SkyBlock update').slice(0, 80), link: /^https:\\/\\/hypixel\\.net\\//.test(String(j.update.link || '')) ? String(j.update.link) : '', lines: j.update.lines.slice(0, 6).map(function (x) { return String(x).slice(0, 200); }) };`],

['news-input-run',
`checkin: (state.ai || {}).checkin || null,`,
`checkin: (state.ai || {}).checkin || null, news: (state.ai || {}).news || null,`],

['news-input-plan',
`checkin: inp.checkin || null, idOf:`,
`checkin: inp.checkin || null, news: inp.news || null, idOf:`],

['news-task',
`if (inp.checkin && inp.checkin.questions && inp.checkin.questions.length) {`,
`if (inp.news && inp.news.lines && inp.news.lines.length && now - inp.news.at < 7 * 864e5) { // 📰 17.11.2026: what a new patch changes for him
    add({ key: 'alert:news', cycle: String(inp.news.at), project: 'hub', section: E_HUB.events, rank: -3, priority: E_P.p2, labels: [],
      content: '📰 ' + inp.news.title + ' · what changes for you · ⚡ 5 min', due: { date: E_local(inp.news.at).date },
      description: E_join([E_block('📰 For you', inp.news.lines), inp.news.link ? E_block('🔗 Patch notes', ['[' + inp.news.title + '](' + inp.news.link + ')']) : ''])
    });
  }
  if (inp.checkin && inp.checkin.questions && inp.checkin.questions.length) {`],

['news-herald-popup',
`(msNew || []).forEach(function (m) { items.push({ id: 'ms|' + m,`,
`var nw = (state.ai || {}).news; // 📰 17.11.2026: a new patch = a Herald toast + Windows popup once
  if (nw && nw.lines && now - nw.at < 3 * 864e5) items.push({ id: 'news|' + nw.at, at: Math.max(nw.at, now - 20 * 60e3), kind: 'alert', title: ('📰 ' + nw.title).slice(0, 80), text: String(nw.lines[0] || '').slice(0, 140), url: idOf('alert:news') ? 'https://app.todoist.com/app/task/' + idOf('alert:news') : '', sound: 'bell', desktop: true, cmds: [] });
  (msNew || []).forEach(function (m) { items.push({ id: 'ms|' + m,`]
];

// whitespace-flexible regex for a literal snippet
function patchRe(find) {
  var parts = find.trim().split(/\s+/).map(function (w) { return w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); });
  return new RegExp(parts.join('\\s*'), 'g');
}
function applyPatches(src) {
  var report = [];
  PATCHES.forEach(function (p) {
    var re = patchRe(p[1]), hits = src.match(re) || [];
    report.push(p[0] + ': ' + hits.length);
    if (hits.length !== 1) throw new Error('patch ' + p[0] + ' matched ' + hits.length + ' times');
    src = src.replace(re, function () { return p[2]; });
  });
  return { src: src, report: report };
}

  var f = DriveApp.getFileById('10jDCoh7dKkFH9xp5QwP-Q3BLNvUp_y3x');
  var src = f.getBlob().getDataAsString('UTF-8');
  if (src.indexOf("17.11.2026: Hypixel never says") >= 0) { Logger.log('already patched'); return; }
  var r = applyPatches(src);
  f.makeCopy('engine_bundle_backup_2026-11-17_before_chat_fixes.js');
  f.setContent(r.src);
  Logger.log('PATCHED ' + src.length + ' -> ' + r.src.length + ' chars · ' + r.report.join(' · '));
}
