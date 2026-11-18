/** One-off (18.11.2026 chat): only SkyBlock in his SkyBlock Todoist + Herald (Hypixel network leveling + achievement claims stay out;
 *  ⚙️ Settings claim.skip). Backs up engine_bundle.js first. Remove after running. */
function patch1811NoNetwork() {
var PATCHES = [
['claims-skip-network',
`E_claims(mm.events, now).forEach(function (c, i) {`,
`var cSkipRaw = (inp.settings || {})['claim.skip'], cSkip = {}; // 18.11.2026: only SkyBlock things in his SkyBlock Todoist (network leveling + achievements stay out)
  String(cSkipRaw === undefined || cSkipRaw === null ? 'leveling, achievement' : cSkipRaw).split(/[,\\s]+/).forEach(function (w) { if (w) cSkip[w.toLowerCase()] = 1; });
  E_claims(mm.events, now).filter(function (c) { return !cSkip[String(c.what).toLowerCase()]; }).forEach(function (c, i) {`]
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
  if (src.indexOf('18.11.2026: only SkyBlock things in his SkyBlock Todoist') >= 0) { Logger.log('already patched'); return; }
  var r = applyPatches(src);
  try { new Function(r.src); } catch (e) { throw new Error('patched bundle does not parse: ' + e.message); }
  f.makeCopy('engine_bundle_backup_2026-11-18_before_claim_skip.js');
  f.setContent(r.src);
  Logger.log('PATCHED ' + src.length + ' -> ' + r.src.length + ' chars · ' + r.report.join(' · '));
}
