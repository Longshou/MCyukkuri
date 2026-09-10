/* Explicit life-history stages. Size alone never decides age or reproduction. */
(function (root) {
  'use strict';
  const clamp = value => Math.max(0, Math.min(1, value));
  function stage(profile, id) {
    const result = profile.stages.find(s => s.id === id);
    if (!result) throw new RangeError('Unknown growth stage: ' + id);
    return result;
  }
  function scaleAt(profile, id, progress, variation = 0.5) {
    if (!Number.isFinite(progress) || !Number.isFinite(variation)) throw new TypeError('Growth parameters must be finite');
    const s = stage(profile, id);
    // Fixed individual variation changes the curve, while shared endpoints preserve continuity.
    const q = Math.pow(clamp(progress), 0.8 + 0.4 * clamp(variation));
    return s.min_scale + (s.max_scale - s.min_scale) * q;
  }
  function dimensions(bounds, scale, units = 16) {
    return bounds.map(([lo, hi]) => (hi - lo) * scale / units);
  }
  const api = {stage, scaleAt, dimensions, clamp};
  root.YukkuriGrowth = api;
  if (typeof module !== 'undefined') module.exports = api;
})(globalThis);
