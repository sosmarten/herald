/** One-off (18.11.2026 chat): Herald 1.11 events → engine (ticked/skipped steps leave the plan for 20 h, island focus from Herald,
 *  ⚙️ Actions "done" column ticks focus steps). Backs up engine_bundle.js first. Remove after running. */
function patch1811Herald() {
var PATCHES = [

// ① focus steps tick themselves from the ⚙️ Actions "done" column (a chat regex, optional " ×N")
['guide-done-column',
`steps.push({ id: t.key, title: head(plain(t)), text: rest(plain(t)), cmds: full ? cmdsOf(full).slice(0, 4) : [], url: url(full || t) });`,
`var ra = (inp.results || []).filter(function (r) { return r.a && r.a.id === t.actionId; })[0], dn = ra ? String(ra.a.done || '').trim() : ''; // 18.11.2026: ⚙️ Actions "done" = the chat line that finishes it
    var st0 = { id: t.key, title: head(plain(t)), text: rest(plain(t)), cmds: full ? cmdsOf(full).slice(0, 4) : [], url: url(full || t) };
    if (dn) { var dm = /^(.*?)(?:\\s*×\\s*(\\d+))?$/.exec(dn); st0.done = { pattern: dm[1], count: Number(dm[2] || 1) }; }
    steps.push(st0);`],

// ② the bridge forwards Herald's events.json (v12): store steps (20 h), pet, area, K-press context; re-plan soon
['receive-herald-events',
`m.receivedAt = now; m.client = body.client || '';`,
`if (body.herald && body.herald.length) { // 18.11.2026: Herald 1.11 events via the bridge
      var hs = m.herald || {}; hs.steps = hs.steps || {}; hs.seen = hs.seen || {}; hs.ctx = hs.ctx || []; hs.guard = hs.guard || [];
      body.herald.forEach(function (x) {
        if (!x || !x.t) return;
        var sk = x.t + '|' + x.kind + '|' + (x.id || x.name || x.title || ''); if (hs.seen[sk]) return; hs.seen[sk] = 1;
        if (x.kind === 'step' && x.id) {
          if (x.how === 'undo') delete hs.steps[x.id]; else hs.steps[x.id] = { how: x.how, t: x.t, title: String(x.title || '').slice(0, 120) };
          if (x.how === 'key' && x.ctx && x.ctx.length) hs.ctx.push({ id: x.id, title: String(x.title || '').slice(0, 120), t: x.t, ctx: x.ctx.slice(-8).map(function (l) { return String(l).slice(0, 200); }) });
        }
        if (x.kind === 'pet') hs.pet = { name: String(x.name || '').slice(0, 40), lvl: Number(x.lvl) || 0, src: String(x.src || ''), t: x.t };
        if (x.kind === 'area') hs.area = { name: String(x.name || '').slice(0, 40), t: x.t };
        if (x.kind === 'guard') hs.guard.push({ t: x.t, title: String(x.title || '').slice(0, 80), lore: (x.lore || []).slice(0, 8).map(function (l) { return String(l).slice(0, 120); }) });
      });
      Object.keys(hs.steps).forEach(function (k) { if (now - hs.steps[k].t > 20 * 3600e3) delete hs.steps[k]; });
      Object.keys(hs.seen).forEach(function (k) { if (now - Number(k.split('|')[0]) > 864e5) delete hs.seen[k]; });
      hs.ctx = hs.ctx.slice(-60); hs.guard = hs.guard.slice(-20);
      m.herald = hs;
      if (body.herald.some(function (x) { return x && (x.kind === 'step' || x.kind === 'area'); })) props.setProperty('ENG_REPLAN_SOON', String(now));
      engLog_('herald', body.herald.length + ' event(s) · ' + body.herald.slice(-3).map(function (x) { return x.kind + (x.how ? ' ' + x.how : '') + ' ' + (x.title || x.name || x.id || ''); }).join(' · ').slice(0, 280));
    }
    m.receivedAt = now; m.client = body.client || '';`],

// ③ planner inputs: what Herald reported
['inputs-herald',
`} else delete state.live;`,
`} else delete state.live;
  try { var hmz = (engReadJson_(ENG_MODS_FILE) || {}).herald || {}; inputs.heraldSteps = hmz.steps || {}; inputs.heraldPet = hmz.pet || null; inputs.heraldArea = hmz.area || null; } catch (e) { inputs.heraldSteps = {}; } // 18.11.2026`],

// ④ a focus step he ticked or skipped in Herald stays out of the plan for 20 h
['next-skip-herald',
`var feas = inp.results.filter(function (r) { return r.feasible && r.a.mode !== 'daily' && r.a.mode !== 'setup'; });`,
`var hSkip = inp.heraldSteps || {}; // 18.11.2026: ticked/skipped in Herald = not again this session (20 h)
  var feas = inp.results.filter(function (r) { return r.feasible && r.a.mode !== 'daily' && r.a.mode !== 'setup' && !hSkip['next:' + r.a.id]; });`],

// ⑤ island focus: Herald's tab-list island (stayed 3+ min, reported within ~1 min) wins over Hypixel's 5-min status
['tick-herald-area',
`var mAct = E_MODE_ACT[mode || ''] || '';`,
`var hA = null; try { hA = ((engReadJson_(ENG_MODS_FILE) || {}).herald || {}).area; } catch (e) { }
    var hFresh = !!(hA && now - hA.t < 6 * 60e3), hMode = hFresh ? (Object.keys(E_MODE_NAMES).filter(function (k) { return E_MODE_NAMES[k] === hA.name; })[0] || '') : '';
    if (hMode && E_MODE_ACT[hMode] && E_MODE_ACT[hMode] !== (E_MODE_ACT[sess.focusMode || ''] || '')) { sess.focusMode = hMode; sess.modeCand = ''; soon = true; engLog_('focus', 'Herald: ' + hA.name + ' 3+ min → ' + E_MODE_ACT[hMode] + ' is the session focus'); }
    var mAct = hFresh ? '' : (E_MODE_ACT[mode || ''] || '');`]
];

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
  if (src.indexOf('18.11.2026: Herald 1.11 events via the bridge') >= 0) { Logger.log('already patched'); return; }
  var r = applyPatches(src);
  try { new Function(r.src); } catch (e) { throw new Error('patched bundle does not parse: ' + e.message); }
  f.makeCopy('engine_bundle_backup_2026-11-18_before_herald_events.js');
  f.setContent(r.src);
  Logger.log('PATCHED ' + src.length + ' -> ' + r.src.length + ' chars · ' + r.report.join(' · '));
}
