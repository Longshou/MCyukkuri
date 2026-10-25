/* Offline on-body preview of the cloth and leaf swaddle shells (not shipped, outputs go to .local/previews).
   The shell maths are a line-by-line port of client/CareWearRenderer: radius profile and skin table of the posed body surface, a thin
   wrap between the base and a rim that dips at the front, the eight cloth styles (cloth, crossover flap, folded band, cuff, lace frill,
   bow, back initial, tiger mark, sole, strap, pouch, pompoms), the single diaper leaf, the flat opened leaf, and clearHair, which lifts
   the hair, sidelocks, braids and accessories over a worn wrap (YukkuriModel.skin). Bodies are posed with the review
   page's RuntimePose and drawn with its software rasterizer; vertex colours are baked into tinted atlas copies of each texture.
   Usage: node preview_wear.cjs --tasks <tasks.json>   (written by preview_wear.py, which also assembles the sheets). */
'use strict';
const fs = require('fs'), path = require('path'), vm = require('vm');
const root = path.resolve(__dirname, '../../../..');
const {RuntimePose} = require(path.join(root, 'preview/review/pose.js'));
const raster = {}; vm.runInNewContext(fs.readFileSync(path.join(root, 'standards/yukkuri-library/runtime/render.js'), 'utf8'), raster);
const assets = path.join(root, 'src/main/resources/assets/mcyukkuri');
const motions = JSON.parse(fs.readFileSync(path.join(assets, 'yukkuri_presentation/motion_corrections.json'), 'utf8')).models;
const fits = JSON.parse(fs.readFileSync(path.join(assets, 'yukkuri_presentation/placement_fit.json'), 'utf8')).models;
const tasks = JSON.parse(fs.readFileSync(process.argv[process.argv.indexOf('--tasks') + 1], 'utf8'));

// ---- CareWearRenderer constants -------------------------------------------------------------------------------------
const BINS = 24, CELLS = 32, RINGS = 9, SKIN_CELLS = CELLS * 3 / 4, CUP_BASE = .015, MOUTH_GUARD = .22, BOW_AT = .11, NEAR_WINDOW = .05, LOW = .1, FABRIC_DENSITY = 1;
const FOLD = 0, FRILL = 1, PLAIN = 0, LETTER = 1, MARK = 2, OUTING = 3, POMPOMS = 4;
const WHITE = [255, 255, 255];
const S = (fabric, body, rollFabric, roll, accent, trim, rim, crossover, fixture, back, front, puff, rollSize, fold) =>
  ({fabric, body, rollFabric, roll, accent, trim, rim, crossover, fixture, back, front, puff, rollSize, fold});
const STYLES = [
  S('knit', [255, 250, 240], 'cotton', [248, 210, 220], [226, 136, 166], [240, 136, 168], FOLD, true, PLAIN, .42, .20, .010, .17, .022),
  S('cotton', [246, 176, 196], 'cotton', [246, 176, 196], WHITE, [222, 64, 58], FRILL, false, PLAIN, .40, .20, .010, .044, 0),
  S('cotton', [206, 64, 60], 'cotton', [206, 64, 60], WHITE, WHITE, FRILL, false, LETTER, .42, .20, .010, .044, 0),
  S('tiger', WHITE, 'fleece', [255, 236, 206], null, null, FOLD, false, MARK, .42, .20, .010, .15, .024),
  S('cotton', [168, 204, 232], 'cotton', [72, 94, 138], null, null, FOLD, false, OUTING, .40, .20, .006, .15, .020),
  S('fleece', [192, 166, 136], 'fleece', [244, 232, 212], [160, 136, 108], [198, 96, 78], FOLD, true, POMPOMS, .48, .19, .018, .19, .032),
  S('gingham', WHITE, 'cotton', [250, 250, 252], [70, 96, 170], [70, 96, 170], FOLD, true, PLAIN, .42, .20, .010, .16, .022),
  S('floral', WHITE, 'cotton', [214, 236, 200], [120, 180, 110], [120, 180, 110], FOLD, true, PLAIN, .42, .20, .010, .16, .022)];
const CLOTH_INSET = .006, SHELL_OUT = .016, FRILL_REACH = .03, FRILL_DROP = .075;
const POUCH_ANGLE = .2, STRAP_SWEEP = 4.0, POUCH_AT = .15, POUCH_HALF = .10, POUCH_LIFT = .02, BACK_MARK_AT = .17, BACK_MARK_HALF = .11;
const CLEAR_MARGIN = .012, CLEAR_REACH = .05, CLEAR_DEPTH = .03, CLEAR_FADE = .2, CLEAR_RIM = .012;
const TUBE = [-1.75, -1.05, -.45, .15, .75, 1.35, 2.4], FOLD_LIGHT = .025, FOLD_PIPING = .03, FOLD_SHADOW = .03;
const STYLE_IDS = ['knit', 'frill', 'letter', 'tiger', 'outing', 'fleece', 'gingham', 'floral'];
const SEAM_AT = -4.0, SEAM_SWEEP = 3.03, SEAM_SLANT = .38, FLAP_LIFT = .016, FLAP_SIDE = -Math.sign(SEAM_SWEEP);
const FRILL_UV = [0, 0, 16, 8], BOW_UV = [16, 0, 16, 12], MARK_UV = [32, 0, 16, 16], POUCH_UV = [48, 0, 16, 16], POMPOM_UV = [0, 8, 8, 8],
  SOLE_UV = [8, 8, 8, 8], STRAP_UV = [0, 16, 8, 8], LETTERS_X = 0, LETTERS_Y = 32;
const LEAF_BACK = .32, LEAF_FRONT = .22, STALK = .14, STALK_OUT = .025, LEAF_HALF = 1.05, CURL = .01, RISE = .22, LEAF_LIFT = .010;
const LEAF_TOP_FRONT = .26, LEAF_TOP_BACK = .50;
const U_STALK = 5 / 32, U_UNDER = .40, U_FRONT = .50, STALK_ROWS = 2, BACK_ROWS = 5, UNDER_ROWS = 3, FRONT_ROWS = 5, ACROSS = 8;
const LEAF_ROWS = STALK_ROWS + BACK_ROWS + UNDER_ROWS + FRONT_ROWS, MAX_WRAP = 1.6, CELL_V = .5, STAINED_V = .5, MAX_SOIL_SHOWN = 3;
const BODY = name => name.startsWith('soft_head_slice_') || name.endsWith('_rump_mark')
  || ['house_body_surface', 'predator_body_surface', 'deficient_body_surface', 'rounded_body_surface'].includes(name);
// YukkuriModel.overWear: parts lifted over a worn wrap (hair, sidelocks, braids, tails, accessories)
const OVER = part => !BODY(part.name) && !part.name.startsWith('face_') && !part.anatomy && !part.name.includes('puff')
  && !part.name.includes('rump') && !part.name.includes('rear_mark');

const bin = (dx, dz) => Math.min(BINS - 1, Math.max(0, Math.floor((Math.atan2(dz, dx) + Math.PI) / (2 * Math.PI) * BINS)));
const angle = b => -Math.PI + 2 * Math.PI * (b + .5) / BINS;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const wrapA = a => Math.atan2(Math.sin(a), Math.cos(a));
const mod = (a, n) => ((a % n) + n) % n;

function profileOf(pts) { // pts: posed body skin x,y,z in blocks
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (let i = 0; i < pts.length; i += 3) {
    minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]); minY = Math.min(minY, pts[i + 1]); maxY = Math.max(maxY, pts[i + 1]);
    minZ = Math.min(minZ, pts[i + 2]); maxZ = Math.max(maxZ, pts[i + 2]);
  }
  const h = maxY - minY, cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
  const R = new Float64Array(CELLS * BINS), F = new Float64Array(CELLS * BINS), A = new Float64Array(CELLS * BINS);
  for (let i = 0; i < pts.length; i += 3) {
    const dx = pts[i] - cx, dz = pts[i + 2] - cz, f = (pts[i + 1] - minY) / h, r = Math.hypot(dx, dz);
    if (f < 0) continue;
    const at = Math.min(CELLS - 1, Math.max(0, Math.floor(f * CELLS))) * BINS + bin(dx, dz);
    if (r > R[at]) { R[at] = r; F[at] = f; A[at] = Math.atan2(dz, dx); }
  }
  // each returns [radius, true angle of that sample] (CareWearRenderer keeps the angle in sampleAngle)
  const radiusAt = (f, b) => {
    let loF = -1, loR = 0, hiF = 2, hiR = 0, loA = 0, hiA = 0;
    const centre = angle(b);
    for (let c = 0; c < CELLS; c++) {
      const at = c * BINS + b, r = R[at];
      if (r <= 0) continue;
      const cf = F[at], off = wrapA(A[at] - centre);
      if (cf <= f) { if (cf > loF) { loF = cf; loR = r; loA = off; } } else if (cf < hiF) { hiF = cf; hiR = r; hiA = off; }
    }
    if (loF < 0) return [hiF > 1 ? 0 : hiR, centre + hiA];
    if (hiF > 1) return [loR, centre + loA];
    const t = (f - loF) / (hiF - loF);
    return [loR + (hiR - loR) * t, centre + loA + (hiA - loA) * t];
  };
  const around3 = (f, b) => { let best = radiusAt(f, b); for (const d of [-1, 1]) { const s = radiusAt(f, (b + d + BINS) % BINS); if (s[0] > best[0]) best = s; } return best; };
  const sampleNear = (f, b) => {
    let best = around3(f, b);
    const lo = Math.max(0, Math.trunc((f - NEAR_WINDOW) * CELLS)), hi = Math.min(CELLS - 1, Math.trunc((f + NEAR_WINDOW) * CELLS));
    for (let c = lo; c <= hi; c++) for (let d = -1; d <= 1; d++) { const at = c * BINS + (b + d + BINS) % BINS; if (R[at] > best[0]) best = [R[at], A[at]]; }
    return best;
  };
  // CareWearRenderer.radiusLow: near the ground only the bin and its neighbours, no height window
  const sampleLow = (f, b) => f >= LOW ? sampleNear(f, b) : around3(f, b);
  return {h, minY, cx, cz, radiusNear: (f, b) => sampleNear(f, b)[0], sampleLow};
}
// CareWearRenderer.hull: radius per bin centre direction to the convex hull of the samples at their true angles
function hullRing(samples) {
  const pts = samples.filter(s => s[0] > 0).map(([r, a]) => [Math.cos(a) * r, Math.sin(a) * r]);
  const radius = samples.map(s => s[0]);
  if (pts.length < 3) { if (!fill(radius)) return null; convexify(radius); return radius; }
  pts.sort((p, q) => p[0] - q[0] || p[1] - q[1]);
  const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const half = list => { const out = []; for (const p of list) { while (out.length >= 2 && cross(out[out.length - 2], out[out.length - 1], p) <= 0) out.pop(); out.push(p); } out.pop(); return out; };
  const H = [...half(pts), ...half(pts.slice().reverse())];
  for (let i = 0; i < BINS; i++) {
    const dx = Math.cos(angle(i)), dz = Math.sin(angle(i));
    let best = 0;
    for (let e = 0; e < H.length; e++) {
      const [px, pz] = H[e], ex = H[(e + 1) % H.length][0] - px, ez = H[(e + 1) % H.length][1] - pz, den = dx * ez - dz * ex;
      if (Math.abs(den) < 1e-9) continue;
      const t = (px * ez - pz * ex) / den, s = (px * dz - pz * dx) / den;
      if (s >= -1e-4 && s <= 1 + 1e-4 && t > best) best = t;
    }
    if (best > 0) radius[i] = best;
  }
  return radius;
}
function fill(r) {
  if (!r.some(x => x > 0)) return false;
  let c = r.slice();
  for (let i = 0; i < BINS; i++) {
    if (c[i] > 0) continue;
    for (let d = 1; d < BINS; d++) { const a = c[(i + d) % BINS], b = c[(i - d + BINS) % BINS]; if (a > 0 || b > 0) { r[i] = a > 0 && b > 0 ? (a + b) / 2 : Math.max(a, b); break; } }
  }
  c = r.slice();
  for (let i = 0; i < BINS; i++) r[i] = Math.max(c[i], (c[(i + 1) % BINS] + c[(i + BINS - 1) % BINS]) / 2);
  return true;
}
function convexify(r) {
  for (let k = 1; k <= 4; k++) for (let i = 0; i < BINS; i++) {
    const a = (i - k + BINS) % BINS, c = (i + k) % BINS, ax = Math.cos(angle(a)) * r[a], az = Math.sin(angle(a)) * r[a];
    const dx = Math.cos(angle(c)) * r[c] - ax, dz = Math.sin(angle(c)) * r[c] - az, ux = Math.cos(angle(i)), uz = Math.sin(angle(i)), den = ux * dz - uz * dx;
    if (Math.abs(den) < 1e-6) continue;
    const lambda = (ax * dz - az * dx) / den;
    if (lambda > r[i]) r[i] = lambda;
  }
}
const side = a => {
  const away = Math.abs(Math.atan2(Math.sin(a + Math.PI / 2), Math.cos(a + Math.PI / 2))), s = clamp((away - .3) / 1.25, 0, 1);
  return s * s * (3 - 2 * s);
};
const rim = (a, front, back) => front + (back - front) * side(a);
const bandOf = (style, a) => style.rim === FRILL ? style.rollSize * .7 : style.rollSize * (.55 + .45 * side(a));
const puffOf = (u, shell, puff) => { const t = Math.min(1, u / .35); return shell * (.45 + .55 * t * t * (3 - 2 * t)) + puff * Math.sin(Math.PI * Math.min(1, u / .7)); };

// quads: {tex, tint, uv: 'repeat' | 'sheet', n:[x,y,z], p:[[x,y,z,u,v] x4]}
const Q = (out, tex, tint, n, p, repeat = false) => out.push({tex, tint, repeat, n, p});

// ---- cloth swaddle ---------------------------------------------------------------------------------------------------
function swaddle(P, style, letter) {
  const out = [], {h, minY, cx, cz} = P, TOP = [], around = skinTable(P);
  if (!around) return out;
  for (let i = 0; i < BINS; i++) TOP.push(rim(angle(i), style.front, style.back));
  const inset = CLOTH_INSET * h, shell = SHELL_OUT * h, puff = style.puff * h;
  const puffAt = u => puffOf(u, shell, puff);
  const topAt = a => { const fb = (wrapA(a) + Math.PI) / (2 * Math.PI) * BINS - .5, lo = mod(Math.floor(fb), BINS), hi = (lo + 1) % BINS, t = fb - Math.floor(fb); return TOP[lo] + (TOP[hi] - TOP[lo]) * t; };
  const point = (f, a, lift) => {
    const w = wrapA(a), top = topAt(a), u = clamp((f - CUP_BASE) / Math.max(1e-4, top - CUP_BASE), 0, 1), at = CUP_BASE + (top - CUP_BASE) * u;
    const r = around(at, a) + inset + puffAt(u) + lift;
    return [cx + Math.cos(w) * r, minY + at * h, cz + Math.sin(w) * r];
  };
  const ringF = (a, k) => CUP_BASE + (topAt(a) - CUP_BASE) * k / (RINGS - 1);
  const seamLead = k => SEAM_AT + SEAM_SWEEP * (1 - SEAM_SLANT * k / (RINGS - 1));
  const ARC = [0];
  for (let i = 0; i < BINS; i++) {
    const a = point(ringF(angle(i), RINGS >> 1), angle(i), 0), c = point(ringF(angle((i + 1) % BINS), RINGS >> 1), angle((i + 1) % BINS), 0);
    ARC.push(ARC[i] + Math.hypot(c[0] - a[0], c[2] - a[2]));
  }
  const tiles = Math.max(1, Math.round(ARC[BINS] * 16 * FABRIC_DENSITY / 16)), uScale = tiles / Math.max(1e-4, ARC[BINS]), vScale = 16 * FABRIC_DENSITY / 16;
  const midA = (i, j) => (angle(i) + angle(j)) / 2 + (j === 0 ? Math.PI : 0);
  // corners [angle, f, u, lift]
  const fabricQuad = (tint, n, corners) => Q(out, style.fabric, tint, n, corners.map(([a, f, u, l]) => { const p = point(f, a, l); return [...p, u, -(p[1] - minY) * vScale]; }), true);
  const roll = style.rollSize * h, shade = style.body.map(v => Math.floor(v * 7 / 10));
  const bandAt = a => bandOf(style, a);
  const flapF = (a, k) => CUP_BASE + (topAt(a) - bandAt(a) - CUP_BASE) * k / (RINGS - 1);
  for (let i = 0; i < BINS; i++) {
    const j = (i + 1) % BINS, ai = angle(i), aj = angle(j), am = midA(i, j), n = [Math.cos(am), 0, Math.sin(am)], u0 = ARC[i] * uScale, u1 = ARC[i + 1] * uScale;
    for (let k = 0; k + 1 < RINGS; k++)
      fabricQuad(style.body, n, [[ai, ringF(ai, k), u0, 0], [aj, ringF(aj, k), u1, 0], [aj, ringF(aj, k + 1), u1, 0], [ai, ringF(ai, k + 1), u0, 0]]);
    const e = .002 * h, bi = TOP[i] - bandAt(ai), bj = TOP[j] - bandAt(aj);
    fabricQuad(shade, n, [[ai, bi - FOLD_SHADOW, u0, e], [aj, bj - FOLD_SHADOW, u1, e], [aj, bj, u1, e], [ai, bi, u0, e]]);
  }
  const lift = FLAP_LIFT * h, meanR = ARC[BINS] / (2 * Math.PI), U = a => .37 + a * meanR * uScale;
  if (style.crossover) {
    const steps = 8, cut = style.body.map(v => Math.floor(v * 4 / 5));
    for (let k = 0; k + 1 < RINGS; k++) {
      const leadA = seamLead(k), leadB = seamLead(k + 1);
      for (let s = 0; s < steps; s++) {
        const a0 = SEAM_AT + (leadA - SEAM_AT) * s / steps, a1 = SEAM_AT + (leadA - SEAM_AT) * (s + 1) / steps;
        const c0 = SEAM_AT + (leadB - SEAM_AT) * s / steps, c1 = SEAM_AT + (leadB - SEAM_AT) * (s + 1) / steps, am = (a0 + a1 + c0 + c1) / 4;
        const la = lift * Math.min(1, s / 3), lb = lift * Math.min(1, (s + 1) / 3);
        fabricQuad(style.body, [Math.cos(am), 0, Math.sin(am)], [[a0, flapF(a0, k), U(a0), la], [a1, flapF(a1, k), U(a1), lb], [c1, flapF(c1, k + 1), U(c1), lb], [c0, flapF(c0, k + 1), U(c0), la]]);
      }
      const am = (leadA + leadB) / 2 - FLAP_SIDE * .2, n = [Math.cos(am), 0, Math.sin(am)], o = FLAP_SIDE * .18;
      fabricQuad(cut, n, [[leadA, flapF(leadA, k), 0, lift], [leadA, flapF(leadA, k), .1, .001 * h], [leadB, flapF(leadB, k + 1), .1, .001 * h], [leadB, flapF(leadB, k + 1), 0, lift]]);
      fabricQuad(shade, n, [[leadA, flapF(leadA, k), 0, .002 * h], [leadA - o, flapF(leadA - o, k), .1, .002 * h], [leadB - o, flapF(leadB - o, k + 1), .1, .002 * h], [leadB, flapF(leadB, k + 1), 0, .002 * h]]);
      if (style.accent) for (let d = 0; d < 4; d += 2) {
        const t0 = d / 4, t1 = (d + 1) / 4, s0 = leadA + (leadB - leadA) * t0, s1 = leadA + (leadB - leadA) * t1;
        const f0 = flapF(leadA, k) + (flapF(leadB, k + 1) - flapF(leadA, k)) * t0, f1 = flapF(leadA, k) + (flapF(leadB, k + 1) - flapF(leadA, k)) * t1;
        const w = FLAP_SIDE * .05, inn = FLAP_SIDE * .03, l = lift + .002 * h;
        fabricQuad(style.accent, [Math.cos(s0), 0, Math.sin(s0)], [[s0 + inn, f0, 0, l], [s0 + inn + w * .4, f0, 0, l], [s1 + inn + w * .4, f1, 0, l], [s1 + inn, f1, 0, l]]);
      }
    }
  }
  // the edge: a flat folded-over band, or the frill styles' small cuff tube
  const ring = (tint, n, u0, u1, v0, v1, ai, aj, ri0, yi0, rj0, yj0, ri1, yi1, rj1, yj1, tex = style.rollFabric, repeat = true) => Q(out, tex, tint, n, [
    [cx + Math.cos(ai) * ri0, yi0, cz + Math.sin(ai) * ri0, u0, v0], [cx + Math.cos(aj) * rj0, yj0, cz + Math.sin(aj) * rj0, u1, v0],
    [cx + Math.cos(aj) * rj1, yj1, cz + Math.sin(aj) * rj1, u1, v1], [cx + Math.cos(ai) * ri1, yi1, cz + Math.sin(ai) * ri1, u0, v1]], repeat);
  const rimAt = i => { const p = point(TOP[i], angle(i), 0); return [Math.hypot(p[0] - cx, p[2] - cz), p[1]]; };
  // CareWearRenderer.foldQuad: upper edge (fi1, fj1, lift1) raised by rise, lower edge (fi0, fj0, lift0)
  const foldQuad = (tint, n, ai, aj, u0, u1, fi1, fj1, l1, fi0, fj0, l0, rise) => {
    const v = (p, r = 0) => [p[0], p[1] + r, p[2]];
    Q(out, style.rollFabric, tint, n, [[...point(fi0, ai, l0), u0], [...point(fj0, aj, l0), u1], [...v(point(fj1, aj, l1), rise), u1], [...v(point(fi1, ai, l1), rise), u0]]
      .map(p => [p[0], p[1], p[2], p[3], -(p[1] - minY) * vScale]), true);
  };
  const brighter = col => col.map(v => Math.min(255, v + 22));
  for (let i = 0; i < BINS; i++) {
    const j = (i + 1) % BINS, ai = angle(i), aj = angle(j), am = midA(i, j), nx = Math.cos(am), nz = Math.sin(am), u0 = ARC[i] * uScale, u1 = ARC[i + 1] * uScale;
    if (style.rim === FRILL) {
      const tube = roll * .5, [r0i, y0i] = rimAt(i), [r0j, y0j] = rimAt(j), ri = r0i + tube, rj = r0j + tube, yi = y0i - .2 * roll, yj = y0j - .2 * roll;
      for (let t = 0; t + 1 < TUBE.length; t++) {
        const c0 = Math.cos(TUBE[t]), s0 = Math.sin(TUBE[t]), c1 = Math.cos(TUBE[t + 1]), s1 = Math.sin(TUBE[t + 1]), mid = (TUBE[t] + TUBE[t + 1]) / 2, mc = Math.cos(mid);
        ring(style.roll, [nx * mc, Math.sin(mid), nz * mc], u0, u1, t / 8, (t + 1) / 8, ai, aj,
          ri + tube * c0, yi + tube * s0, rj + tube * c0, yj + tube * s0, ri + tube * c1, yi + tube * s1, rj + tube * c1, yj + tube * s1);
      }
      continue;
    }
    const th = style.fold * h, ti = TOP[i], tj = TOP[j], bi = ti - bandAt(ai), bj = tj - bandAt(aj), lit = brighter(style.roll), dark = style.roll.map(v => Math.floor(v * 3 / 5));
    foldQuad(lit, [nx * .4, .9, nz * .4], ai, aj, u0, u1, ti, tj, 0, ti, tj, th, .006 * h);
    const li = ti - FOLD_LIGHT, lj = tj - FOLD_LIGHT, pipe = style.accent ? FOLD_PIPING : 0;
    foldQuad(lit, [nx, 0, nz], ai, aj, u0, u1, ti, tj, th, li, lj, th, 0);
    foldQuad(style.roll, [nx, 0, nz], ai, aj, u0, u1, li, lj, th, bi + pipe, bj + pipe, th, 0);
    if (pipe > 0) foldQuad(style.accent, [nx, 0, nz], ai, aj, u0, u1, bi + pipe, bj + pipe, th, bi, bj, th, 0);
    foldQuad(dark, [nx * .4, -.9, nz * .4], ai, aj, u0, u1, bi, bj, th, bi, bj, 0, 0);
  }
  // trim
  const T = 'trim', sheet = (corners, tint, n) => Q(out, T, tint, n, corners);
  if (style.rim === FRILL) {
    const drop = FRILL_DROP * h, reach = FRILL_REACH * h, u0 = FRILL_UV[0] / 64, u1 = (FRILL_UV[0] + FRILL_UV[2]) / 64, v0 = FRILL_UV[1] / 64, v1 = (FRILL_UV[1] + FRILL_UV[3]) / 64;
    for (let i = 0; i < BINS; i++) {
      const j = (i + 1) % BINS, ai = angle(i), aj = angle(j), am = midA(i, j);
      const [ri0, yi0] = rimAt(i), [rj0, yj0] = rimAt(j), ri = ri0 + roll * .4, rj = rj0 + roll * .4, yi = yi0 - roll * .7, yj = yj0 - roll * .7;
      const oi = reach + (i % 2 === 0 ? .014 : -.004) * h, oj = reach + (j % 2 === 0 ? .014 : -.004) * h;
      ring(style.accent, [Math.cos(am) * .7, -.7, Math.sin(am) * .7], u1, u0, v0, v1, ai, aj, ri, yi, rj, yj, ri + oi, yi - drop, rj + oj, yj - drop, T, false);
    }
  }
  if (style.fixture === PLAIN && style.trim) {
    const a = style.crossover ? seamLead(RINGS - 1) : -Math.PI / 2, bowH = .19 * h, w = .26 * h;
    const f = style.crossover ? topAt(a) - bandAt(a) * .5 : BOW_AT, at = point(f, a, (style.crossover ? style.fold + .015 : style.rollSize + FRILL_REACH + .005) * h);
    const nx = Math.cos(a), nz = Math.sin(a), tx = -nz, tz = nx, turn = .3, cs = Math.cos(turn) * w / 2, sn = Math.sin(turn) * w / 2;
    const u0 = BOW_UV[0] / 64, um = (BOW_UV[0] + BOW_UV[2] / 2) / 64, u1 = (BOW_UV[0] + BOW_UV[2]) / 64, v0 = BOW_UV[1] / 64, v1 = (BOW_UV[1] + BOW_UV[3]) / 64;
    for (const side of [-1, 1]) {
      const ex = at[0] + side * tx * cs + nx * sn, ez = at[2] + side * tz * cs + nz * sn, ue = side > 0 ? u0 : u1;
      sheet([[at[0], at[1] - bowH / 2, at[2], um, v1], [ex, at[1] - bowH / 2, ez, ue, v1], [ex, at[1] + bowH / 2, ez, ue, v0], [at[0], at[1] + bowH / 2, at[2], um, v0]], style.trim, [nx, 0, nz]);
    }
  }
  const decal = (tint, tx, ty, tw, th, a0, f0, halfW, halfH, lift) => {
    const centre = point(f0, a0, lift), half = halfW / Math.max(1e-4, Math.hypot(centre[0] - cx, centre[2] - cz)), n = 4;
    for (let gi = 0; gi < n; gi++) for (let gj = 0; gj < n; gj++) {
      const aA = a0 - half + 2 * half * gi / n, aB = a0 - half + 2 * half * (gi + 1) / n, fA = f0 + halfH - 2 * halfH * gj / n, fB = f0 + halfH - 2 * halfH * (gj + 1) / n;
      const uA = (tx + tw * (1 - gi / n)) / 64, uB = (tx + tw * (1 - (gi + 1) / n)) / 64, vA = (ty + th * gj / n) / 64, vB = (ty + th * (gj + 1) / n) / 64, am = (aA + aB) / 2;
      sheet([[...point(fB, aA, lift), uA, vB], [...point(fB, aB, lift), uB, vB], [...point(fA, aB, lift), uB, vA], [...point(fA, aA, lift), uA, vA]], tint, [Math.cos(am), 0, Math.sin(am)]);
    }
  };
  if (style.fixture === LETTER) decal(style.trim, LETTERS_X + letter % 8 * 8, LETTERS_Y + Math.floor(letter / 8) * 8, 8, 8, Math.PI / 2, BACK_MARK_AT, BACK_MARK_HALF * h, BACK_MARK_HALF, .008 * h);
  if (style.fixture === MARK) {
    decal(WHITE, ...MARK_UV, Math.PI / 2, BACK_MARK_AT, BACK_MARK_HALF * h, BACK_MARK_HALF, .008 * h);
    decal(WHITE, ...MARK_UV, -Math.PI / 2, .065, .055 * h, .055, .008 * h);
  }
  if (style.fixture === OUTING) {
    const soleTop = .10, o = .016 * h, su0 = SOLE_UV[0] / 64, su1 = (SOLE_UV[0] + SOLE_UV[2]) / 64;
    for (let i = 0; i < BINS; i++) {
      const j = (i + 1) % BINS, ai = angle(i), aj = angle(j), am = midA(i, j);
      const lo0 = point(CUP_BASE, ai, o), lo1 = point(CUP_BASE, aj, o), hi0 = point(soleTop, ai, o), hi1 = point(soleTop, aj, o), in0 = point(soleTop, ai, 0), in1 = point(soleTop, aj, 0);
      sheet([[...lo0, su1, (SOLE_UV[1] + 8) / 64], [...lo1, su0, (SOLE_UV[1] + 8) / 64], [...hi1, su0, (SOLE_UV[1] + 1) / 64], [...hi0, su1, (SOLE_UV[1] + 1) / 64]], WHITE, [Math.cos(am), 0, Math.sin(am)]);
      sheet([[...hi0, su1, (SOLE_UV[1] + 1) / 64], [...hi1, su0, (SOLE_UV[1] + 1) / 64], [...in1, su0, SOLE_UV[1] / 64], [...in0, su1, SOLE_UV[1] / 64]], WHITE, [0, 1, 0]);
    }
    const start = POUCH_ANGLE, end = start + STRAP_SWEEP, steps = 16, half = .035, rimEnd = topAt(end) - bandAt(end) - .02, from = POUCH_AT + POUCH_HALF;
    const strapF = a => { const t = (a - start) / (end - start); return from + (rimEnd - from) * t - .06 * Math.sin(Math.PI * t); };
    for (let s = 0; s < steps; s++) {
      const a0 = start + (end - start) * s / steps, a1 = start + (end - start) * (s + 1) / steps, f0 = strapF(a0), f1 = strapF(a1), am = (a0 + a1) / 2;
      const u0 = STRAP_UV[0] / 64, u1 = (STRAP_UV[0] + STRAP_UV[2]) / 64, v0 = STRAP_UV[1] / 64, v1 = (STRAP_UV[1] + STRAP_UV[3]) / 64, l = .012 * h;
      sheet([[...point(f0 - half, a0, l), u0, v0], [...point(f1 - half, a1, l), u0, v1], [...point(f1 + half, a1, l), u1, v1], [...point(f0 + half, a0, l), u1, v0]], WHITE, [Math.cos(am), 0, Math.sin(am)]);
    }
    decal(WHITE, ...POUCH_UV, start, POUCH_AT, POUCH_HALF * h, POUCH_HALF, POUCH_LIFT * h);
  }
  if (style.fixture === POMPOMS) for (let end = 0; end < 2; end++) {
    const a0 = seamLead(RINGS - 1), f = topAt(a0) - bandAt(a0) * .5, a = a0 + (end === 0 ? -.13 : .1);
    const at = point(f - (end === 0 ? .07 : .11), a, (style.fold + .045) * h), s = .042 * h;
    const u0 = POMPOM_UV[0] / 64, u1 = (POMPOM_UV[0] + POMPOM_UV[2]) / 64, v0 = POMPOM_UV[1] / 64, v1 = (POMPOM_UV[1] + POMPOM_UV[3]) / 64;
    const corner = [...Array(8)].map((_, n) => [at[0] + (n & 1 ? s : -s), at[1] + (n & 2 ? s : -s), at[2] + (n & 4 ? s : -s)]);
    const faces = [[0, 4, 6, 2], [5, 1, 3, 7], [0, 1, 5, 4], [6, 7, 3, 2], [1, 0, 2, 3], [4, 5, 7, 6]], normals = [[-1, 0, 0], [1, 0, 0], [0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1]];
    faces.forEach((q, f) => sheet([[...corner[q[0]], u0, v1], [...corner[q[1]], u1, v1], [...corner[q[2]], u1, v0], [...corner[q[3]], u0, v0]], style.trim, normals[f]));
  }
  return out;
}

// ---- leaf swaddle ----------------------------------------------------------------------------------------------------
// CareWearRenderer.skinTable + radiusAround: hull radius per height cell centre, read bilinearly
function skinTable(P) {
  const near = [];
  for (let c = 0; c < SKIN_CELLS; c++) {
    const row = hullRing([...Array(BINS)].map((_, i) => P.sampleLow((c + .5) / CELLS, i)));
    if (!row) return null;
    near.push(row);
  }
  return (f, a) => {
    const w = wrapA(a), fb = (w + Math.PI) / (2 * Math.PI) * BINS - .5, fc = clamp(f * CELLS - .5, 0, near.length - 1);
    const lo = mod(Math.floor(fb), BINS), hi = (lo + 1) % BINS, c0 = Math.trunc(fc), c1 = Math.min(near.length - 1, c0 + 1), t = fb - Math.floor(fb), q = fc - c0;
    const r0 = near[c0][lo] + (near[c0][hi] - near[c0][lo]) * t, r1 = near[c1][lo] + (near[c1][hi] - near[c1][lo]) * t;
    return r0 + (r1 - r0) * q;
  };
}
function leafWrap(P, soil) {
  const out = [], {h, minY, cx, cz} = P, around = skinTable(P);
  if (!around) return out;
  const base = LEAF_LIFT * h, half = LEAF_HALF * h, soilHeight = soil <= 0 ? -1 : .04 + .09 * Math.min(3, soil);
  const leafPoint = (back, f, lateral, lift) => {
    const meridian = back ? Math.PI / 2 : -Math.PI / 2, d = clamp(lateral / Math.max(1e-4, around(f, meridian)), -MAX_WRAP, MAX_WRAP), a = back ? meridian + d : meridian - d;
    const r = around(f, a) + lift;
    return [cx + Math.cos(a) * r, minY + f * h, cz + Math.sin(a) * r, a];
  };
  const grid = [], along = [];
  for (let row = 0; row <= LEAF_ROWS; row++) {
    const part = row <= STALK_ROWS ? 0 : row <= STALK_ROWS + BACK_ROWS ? 1 : row < STALK_ROWS + BACK_ROWS + UNDER_ROWS ? 2 : 3;
    const s = [row / STALK_ROWS, (row - STALK_ROWS) / BACK_ROWS, (row - STALK_ROWS - BACK_ROWS) / UNDER_ROWS, (row - STALK_ROWS - BACK_ROWS - UNDER_ROWS) / FRONT_ROWS][part];
    along.push([U_STALK * s, U_STALK + (U_UNDER - U_STALK) * s, U_UNDER + (U_FRONT - U_UNDER) * s, U_FRONT + (1 - U_FRONT) * s][part]);
    for (let j = 0; j <= ACROSS; j++) {
      const edge = (j / ACROSS - .5) * 2, lateral = edge * half, cup = RISE * edge * edge;
      if (part === 2) {
        // CareWearRenderer: straight across under the middle, round the flank at the cupped height towards the margins
        const fs = CUP_BASE + cup, w = edge * edge, A = leafPoint(true, fs, lateral, base), B = leafPoint(false, fs, lateral, base);
        const d = A[3] - Math.PI / 2, arc = A[3] + ((edge >= 0 ? Math.PI : -Math.PI) - 2 * d) * s, rr = around(fs, arc) + base;
        const chordX = A[0] + (B[0] - A[0]) * s, chordZ = A[2] + (B[2] - A[2]) * s, chordY = minY + (.006 + (fs - .006) * w) * h;
        grid.push([chordX + (cx + Math.cos(arc) * rr - chordX) * w, chordY, chordZ + (cz + Math.sin(arc) * rr - chordZ) * w, 0]);
        continue;
      }
      const back = part < 2, f = part === 0 ? LEAF_BACK + STALK * (1 - s)
        : Math.min(.48, cup + (part === 1 ? LEAF_BACK + (CUP_BASE - LEAF_BACK) * s : CUP_BASE + (LEAF_FRONT - CUP_BASE) * s));
      const lift = base + CURL * h * edge * edge + (part === 0 ? STALK_OUT * h * (1 - s) * (1 - s) : 0);
      grid.push([...leafPoint(back, f, lateral, lift).slice(0, 3), f]);
    }
  }
  for (let i = 0; i < LEAF_ROWS; i++) {
    const under = i >= STALK_ROWS + BACK_ROWS && i < STALK_ROWS + BACK_ROWS + UNDER_ROWS;
    for (let j = 0; j < ACROSS; j++) {
      const g = (ii, jj) => grid[ii * (ACROSS + 1) + jj], c00 = g(i, j), c10 = g(i + 1, j), c11 = g(i + 1, j + 1), c01 = g(i, j + 1);
      const mx = (c00[0] + c11[0]) / 2 - cx, mz = (c00[2] + c11[2]) / 2 - cz, ml = Math.hypot(mx, mz) + 1e-6;
      const cell = (c00[3] + c11[3]) / 2 < soilHeight ? STAINED_V : 0, u0 = along[i], u1 = along[i + 1], v0 = cell + CELL_V * j / ACROSS, v1 = cell + CELL_V * (j + 1) / ACROSS;
      // CareWearRenderer darkens the base of a full wrap per vertex; the preview tints whole quads whose middle lies that low
      const blotch = soil >= MAX_SOIL_SHOWN && (c00[3] + c11[3]) / 2 < .05;
      const side = under ? Math.abs((j + .5) / ACROSS - .5) * 2 : 1;
      Q(out, 'leaf', blotch ? [200, 172, 140] : WHITE, [mx / ml * side, -(1 - side), mz / ml * side],
        [[...c00.slice(0, 3), u0, v0], [...c10.slice(0, 3), u1, v0], [...c11.slice(0, 3), u1, v1], [...c01.slice(0, 3), u0, v1]]);
    }
  }
  return out;
}
function groundLeaf(P) {
  const out = [], {h, cx, cz} = P;
  let reach = 0;
  for (let i = 0; i < BINS; i++) reach = Math.max(reach, P.radiusNear(.2, i));
  if (reach <= 0) return out;
  const length = reach * 1.45, width = reach * 1.1, y = .004 * h, back = cz + length * 1.05, front = cz - length * .95;
  Q(out, 'leaf', WHITE, [0, 1, 0], [[cx - width, y, back, 0, 0], [cx + width, y, back, 0, CELL_V], [cx + width, y, front, 1, CELL_V], [cx - width, y, front, 1, 0]]);
  return out;
}

// ---- atlas: tinted copies of the raw textures, built on demand --------------------------------------------------------
const ATLAS = 2048, atlas = new Uint8Array(ATLAS * ATLAS * 4), regions = new Map(), raws = {};
let shelfX = 0, shelfY = 0, shelfH = 0;
for (const [name, t] of Object.entries(tasks.textures)) raws[name] = {w: t.w, h: t.h, data: fs.readFileSync(t.file)};
function place(w, hgt) {
  if (shelfX + w > ATLAS) { shelfX = 0; shelfY += shelfH; shelfH = 0; }
  const at = [shelfX, shelfY]; shelfX += w; shelfH = Math.max(shelfH, hgt);
  if (shelfY + hgt > ATLAS) throw Error('preview atlas full');
  return at;
}
const REPEAT = 16; // fabric tiles are copied REPEAT x REPEAT times; u and v are shifted into the middle copies
function region(name, tint, repeat) {
  const key = name + '|' + tint.join(',') + '|' + repeat;
  if (regions.has(key)) return regions.get(key);
  const raw = raws[name], copies = repeat ? REPEAT : 1, w = raw.w * copies, hgt = raw.h * copies, [x0, y0] = place(w, hgt);
  for (let y = 0; y < hgt; y++) for (let x = 0; x < w; x++) {
    const s = ((y % raw.h) * raw.w + x % raw.w) * 4, d = ((y0 + y) * ATLAS + x0 + x) * 4;
    atlas[d] = raw.data[s] * tint[0] / 255; atlas[d + 1] = raw.data[s + 1] * tint[1] / 255; atlas[d + 2] = raw.data[s + 2] * tint[2] / 255; atlas[d + 3] = raw.data[s + 3];
  }
  const r = {x0, y0, w, hgt, copies};
  regions.set(key, r);
  return r;
}
function uvIn(r, u, v, repeat) {
  if (repeat) { u = clamp(u + REPEAT / 2, 0, REPEAT - 1e-4) / REPEAT; v = clamp(v + REPEAT / 2, 0, REPEAT - 1e-4) / REPEAT; }
  return [(r.x0 + clamp(u, 0, .9999) * r.w) / ATLAS, (r.y0 + clamp(v, 0, .9999) * r.hgt) / ATLAS];
}

// ---- scene assembly ---------------------------------------------------------------------------------------------------
const poses = new Map();
function poseFor(id) {
  if (!poses.has(id)) { const model = JSON.parse(fs.readFileSync(path.join(assets, 'yukkuri', id + '.json'), 'utf8')); poses.set(id, new RuntimePose(model, 1, motions[id], fits[id] || null)); }
  return poses.get(id);
}
function emitQuad(out, q, scale, at, yaw) {
  const r = region(q.tex, q.tint, q.repeat);
  const c = Math.cos(yaw), s = Math.sin(yaw), put = (x, y, z) => [(x * c + z * s) * scale + at[0], y * scale + at[1], (-x * s + z * c) * scale + at[2]];
  const n = [q.n[0] * c + q.n[2] * s, q.n[1], -q.n[0] * s + q.n[2] * c];
  const v = q.p.map(([x, y, z, u, vv]) => [...put(x * 16, y * 16, z * 16), ...uvIn(r, u, vv, q.repeat)]);
  for (const [tri, sign] of [[[0, 1, 2, 0, 2, 3], 1], [[0, 2, 1, 0, 3, 2], -1]]) // drawn without culling in game: both windings
    for (const k of tri) out.push(...v[k], n[0] * sign, n[1] * sign, n[2] * sign);
}
function initial(id) {
  const name = id.startsWith('house_') ? id.slice(6) : id, c = name.toUpperCase().charCodeAt(0);
  return c < 65 || c > 90 ? 24 : c - 65;
}
// ---- CareWearRenderer.wrapThickness / clearHair: hair over the wrap -----------------------------------------------------
function wrapThickness(style, f, a) {
  let top, thick;
  if (style) {
    top = rim(a, style.front, style.back);
    const u = clamp((f - CUP_BASE) / Math.max(1e-4, top - CUP_BASE), 0, 1), band = bandOf(style, a), edge = clamp((f - (top - band - .04)) / .04, 0, 1);
    thick = CLOTH_INSET + puffOf(u, SHELL_OUT, style.puff) + (style.crossover ? FLAP_LIFT : 0) + (style.fixture === OUTING ? POUCH_LIFT : 0)
      + edge * edge * (3 - 2 * edge) * (style.rim === FRILL ? style.rollSize + FRILL_REACH : style.fold);
  } else {
    top = rim(a, LEAF_TOP_FRONT, LEAF_TOP_BACK);
    const back = Math.abs(wrapA(a - Math.PI / 2));
    thick = LEAF_LIFT + CURL + (back < .45 && f > LEAF_BACK - .04 ? STALK_OUT : 0);
  }
  const fade = clamp(1 - (f - top - CLEAR_RIM) / CLEAR_FADE, 0, 1);
  return fade <= 0 ? 0 : (thick + CLEAR_MARGIN) * fade;
}
function clearHair(P, around, style, raw, model) {
  const {h, minY, cx, cz} = P, topMax = (style ? Math.max(style.back, style.front) : LEAF_TOP_BACK) + CLEAR_RIM + CLEAR_FADE;
  for (const part of model.parts) if (OVER(part)) for (let i = part.start; i < part.start + part.count; i++) {
    const x = raw[i * 8] / 16 - cx, z = raw[i * 8 + 2] / 16 - cz, f = (raw[i * 8 + 1] / 16 - minY) / h;
    if (f < 0 || f > topMax) continue;
    const a = Math.atan2(z, x), thick = wrapThickness(style, f, a);
    if (thick <= 0) continue;
    const r = Math.hypot(x, z);
    if (r < 1e-5) continue;
    const skin = around(f, a), g = (r - skin) / h, reach = thick + CLEAR_REACH;
    if (g <= -CLEAR_DEPTH || g >= reach) continue;
    const lifted = g + thick * (g >= 0 ? 1 - g / reach : 1 + g / CLEAR_DEPTH), s = (skin + lifted * h) / r;
    raw[i * 8] = (cx + x * s) * 16; raw[i * 8 + 2] = (cz + z * s) * 16;
  }
}
function actorVertices(actor) {
  const pose = poseFor(actor.model), duration = pose.clips[actor.action].duration;
  pose.expression(actor.face || 'neutral');
  const raw = pose.frame(actor.action, duration * (actor.phase ?? 0)).slice(), model = pose.model;
  const pts = [];
  for (const part of model.parts) if (BODY(part.name)) for (let i = part.start; i < part.start + part.count; i++)
    pts.push(raw[i * 8] / 16, raw[i * 8 + 1] / 16, raw[i * 8 + 2] / 16);
  const P = actor.wear ? profileOf(pts) : null, style = actor.wear === 'cloth' ? STYLES[STYLE_IDS.indexOf(actor.style || 'knit')] : null;
  // YukkuriModel.skin: with a wrap on, the hair is lifted over it before the body is drawn
  if (actor.wear === 'cloth' || actor.wear === 'leaf') { const around = skinTable(P); if (around) clearHair(P, around, style, raw, model); }
  const scale = actor.scale ?? 1, at = actor.at || [0, 0, 0], yaw = (actor.yaw || 0) * Math.PI / 180, c = Math.cos(yaw), s = Math.sin(yaw);
  const skin = region('entity:' + actor.model + ':' + (actor.face || 'neutral'), WHITE, false);
  const out = [];
  for (let i = 0; i < raw.length; i += 8) {
    const x = raw[i], z = raw[i + 2], nx = raw[i + 5], nz = raw[i + 7];
    out.push((x * c + z * s) * scale + at[0], raw[i + 1] * scale + at[1], (-x * s + z * c) * scale + at[2],
      (skin.x0 + raw[i + 3] * skin.w) / ATLAS, (skin.y0 + raw[i + 4] * skin.hgt) / ATLAS, nx * c + nz * s, raw[i + 6], -nx * s + nz * c);
  }
  if (actor.wear) {
    const quads = actor.wear === 'cloth' ? swaddle(P, style, initial(actor.model))
      : actor.wear === 'open' ? groundLeaf(P) : leafWrap(P, actor.soil || 0);
    for (const q of quads) emitQuad(out, q, scale, at, yaw);
  }
  return out;
}
const result = [];
const verts = tasks.panels.map(panel => Float64Array.from(panel.actors.flatMap(actorVertices)));
tasks.panels.forEach((panel, n) => {
  const pixels = raster.renderModel(verts[n], atlas, tasks.width, tasks.height, panel.azimuth, panel.elevation ?? .12, panel.span ?? 11,
    {centerY: panel.centerY ?? 7.5, textureSize: ATLAS});
  const file = path.join(tasks.work, panel.id + '.rgba');
  fs.writeFileSync(file, Buffer.from(pixels));
  result.push({id: panel.id, file});
});
fs.writeFileSync(path.join(tasks.work, 'result.json'), JSON.stringify(result));
console.log('Rendered ' + result.length + ' wear panels.');
