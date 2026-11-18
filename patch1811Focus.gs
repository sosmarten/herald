/** One-off (18.11.2026 chat): the session plan follows the activity he is actually playing. Backs up engine_bundle.js first. Remove after running. */
function patch1811Focus() {
var PATCHES = [

// ① status tick: an activity island seen on 2 ticks in a row (~5 min, so a quick trap / Kat warp doesn't flip it)
//    becomes the session focus and re-plans right away. Cleared at login.
['tick-focus-mode',
`if (fresh || soon || now - (sess.lastRun || 0) > 20 * 60e3) { sess.lastRun = now; rerun = true; }`,
`if (fresh) { sess.focusMode = ''; sess.modeCand = ''; }
    // 18.11.2026: the plan follows where he plays (island seen on 2 ticks in a row → that activity is the focus, re-plan now)
    var mAct = E_MODE_ACT[mode || ''] || '';
    if (mAct && mAct !== (E_MODE_ACT[sess.focusMode || ''] || '')) {
      if (sess.modeCand === mode) { sess.focusMode = mode; sess.modeCand = ''; soon = true; engLog_('focus', 'playing ' + mAct + ' (' + (E_MODE_NAMES[mode] || mode) + ') → session focus'); }
      else sess.modeCand = mode;
    } else sess.modeCand = '';
    if (fresh || soon || now - (sess.lastRun || 0) > 20 * 60e3) { sess.lastRun = now; rerun = true; }`],

// ② pass it to the planner
['live-focus-input',
`mode: lsess.mode || '' };`,
`mode: lsess.mode || '', focusMode: lsess.focusMode || '' };`],

// ③ planner: the activity he's playing right now IS the focus (if it has a grind he can do); a 2nd grind can still ride along in 2 h+ sessions
['next-live-focus',
`var left = Math.max(0, budget - used), best = { val: -1 };`,
`// 18.11.2026: playing an activity right now (its island 5+ min) = it is the focus, if it has a grind he can do
  var liveAct = (inp.live || {}).on ? (E_MODE_ACT[inp.live.focusMode || ''] || '') : '';
  var liveG = liveAct ? grinds.filter(function (r) { return r.a.activity === liveAct; }) : [];
  if (liveG.length) grinds = liveG.concat(grinds.filter(function (r) { return r.a.activity !== liveAct; }));
  var left = Math.max(0, budget - used), best = { val: -1 };`],

['next-live-focus-loop',
`grinds.slice(0, 5).forEach(function (g) {`,
`grinds.slice(0, liveG.length ? 1 : 5).forEach(function (g) {`]
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
  if (src.indexOf('18.11.2026: the plan follows where he plays') >= 0) { Logger.log('already patched'); return; }
  var r = applyPatches(src);
  f.makeCopy('engine_bundle_backup_2026-11-18_before_live_focus.js');
  f.setContent(r.src);
  Logger.log('PATCHED ' + src.length + ' -> ' + r.src.length + ' chars · ' + r.report.join(' · '));
}
