/* Offline on-body preview of the cloth and leaf swaddle shells (not shipped, outputs go to .local/previews).
   The shell maths are a line-by-line port of client/CareWearRenderer: radius profile of the posed body surface, rings between
   the base and a rim that dips at the front, the knit cup with folded lip and hem, four overlapping leaves, and the flat support
   leaves of an opened wrap. Bodies are posed with the review page's RuntimePose and drawn with its software rasterizer.
   Usage: node preview_wear.cjs --tasks <tasks.json>   (written by preview_wear.py, which also assembles the sheet). */
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
const BINS = 24, CELLS = 32, RINGS = 5, LEAF_STEPS = 10;
const CUP_BASE = .015, CUP_BACK = .40, CUP_FRONT = .16, WOOL_DENSITY = 4, SEAM_AT = -2.3, SEAM_SWEEP = .55, SEAM_SLANT = .6;
const LAYER = .014, TIP_LIFT = .07;
const LEAVES = [[2.52, .24, .62, .28, .50, 0, 0], [2.30, .17, 4.08, .27, .46, 1, .25], [.84, .17, -.94, .27, .46, 1, .25], [-.80, .075, -2.34, .08, .26, 4, 0]];
const CELL_V = .25, STAINED_V = .5;
const ARC_SCALE = .73, ACROSS = 6, MOUTH_GUARD = .17, GUARD_HALF = .35;
const GROUND = [[.35, 0], [-1.95, .25]];
const BODY = name => name.startsWith('soft_head_slice_') || name.endsWith('_rump_mark')
  || ['house_body_surface', 'predator_body_surface', 'deficient_body_surface', 'rounded_body_surface'].includes(name);
// YukkuriModel.posedHull: the body plus hair hanging at the back and rear sides
const HULL = name => BODY(name) || (name.includes('hair') || name.includes('lock')) && (name.includes('back') || name.includes('rear'));

const bin = (dx, dz) => Math.min(BINS - 1, Math.max(0, Math.floor((Math.atan2(dz, dx) + Math.PI) / (2 * Math.PI) * BINS)));
const angle = b => -Math.PI + 2 * Math.PI * (b + .5) / BINS;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

function shellFor(pts, hull) { // pts: body x,y,z in blocks (heights); hull: body and back hair (radii)
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (let i = 0; i < pts.length; i += 3) {
    minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]); minY = Math.min(minY, pts[i + 1]); maxY = Math.max(maxY, pts[i + 1]);
    minZ = Math.min(minZ, pts[i + 2]); maxZ = Math.max(maxZ, pts[i + 2]);
  }
  const h = maxY - minY, cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
  const R = new Float64Array(CELLS * BINS), F = new Float64Array(CELLS * BINS);
  for (let i = 0; i < hull.length; i += 3) {
    const dx = hull[i] - cx, dz = hull[i + 2] - cz, f = (hull[i + 1] - minY) / h, r = Math.hypot(dx, dz);
    if (f < 0) continue;
    const at = Math.min(CELLS - 1, Math.max(0, Math.floor(f * CELLS))) * BINS + bin(dx, dz);
    if (r > R[at]) { R[at] = r; F[at] = f; }
  }
  const radiusAt = (f, b) => {
    let loF = -1, loR = 0, hiF = 2, hiR = 0;
    for (let c = 0; c < CELLS; c++) {
      const at = c * BINS + b, r = R[at];
      if (r <= 0) continue;
      const cf = F[at];
      if (cf <= f) { if (cf > loF) { loF = cf; loR = r; } } else if (cf < hiF) { hiF = cf; hiR = r; }
    }
    if (loF < 0) return hiF > 1 ? 0 : hiR;
    if (hiF > 1) return loR;
    return loR + (hiR - loR) * (f - loF) / (hiF - loF);
  };
  const radiusNear = (f, b) => {
    let r = Math.max(radiusAt(f, b), radiusAt(f, (b + 1) % BINS), radiusAt(f, (b + BINS - 1) % BINS));
    const lo = Math.max(0, Math.trunc((f - .05) * CELLS)), hi = Math.min(CELLS - 1, Math.trunc((f + .05) * CELLS));
    for (let c = lo; c <= hi; c++) for (let d = -1; d <= 1; d++) r = Math.max(r, R[c * BINS + (b + d + BINS) % BINS]);
    return r;
  };
  const fill = r => {
    if (!r.some(x => x > 0)) return false;
    let c = r.slice();
    for (let i = 0; i < BINS; i++) {
      if (c[i] > 0) continue;
      for (let d = 1; d < BINS; d++) { const a = c[(i + d) % BINS], b = c[(i - d + BINS) % BINS]; if (a > 0 || b > 0) { r[i] = a > 0 && b > 0 ? (a + b) / 2 : Math.max(a, b); break; } }
    }
    c = r.slice();
    for (let i = 0; i < BINS; i++) r[i] = Math.max(c[i], (c[(i + 1) % BINS] + c[(i + BINS - 1) % BINS]) / 2);
    return true;
  };
  const convexify = r => {
    for (let k = 1; k <= 4; k++) for (let i = 0; i < BINS; i++) {
      const a = (i - k + BINS) % BINS, c = (i + k) % BINS, ax = Math.cos(angle(a)) * r[a], az = Math.sin(angle(a)) * r[a];
      const dx = Math.cos(angle(c)) * r[c] - ax, dz = Math.sin(angle(c)) * r[c] - az, ux = Math.cos(angle(i)), uz = Math.sin(angle(i)), den = ux * dz - uz * dx;
      if (Math.abs(den) < 1e-6) continue;
      const lambda = (ax * dz - az * dx) / den;
      if (lambda > r[i]) r[i] = lambda;
    }
  };
  const rim = (a, front, back) => {
    const away = Math.abs(Math.atan2(Math.sin(a + Math.PI / 2), Math.cos(a + Math.PI / 2))), s = clamp((away - .45) / 1.65, 0, 1);
    return front + (back - front) * s * s * (3 - 2 * s);
  };
  const rings = (front, back) => {
    const TOP = [], RING = [];
    for (let i = 0; i < BINS; i++) TOP.push(rim(angle(i), front, back));
    for (let k = 0; k < RINGS; k++) {
      const u = k / (RINGS - 1), ring = [];
      for (let i = 0; i < BINS; i++) ring.push(radiusNear(CUP_BASE + (TOP[i] - CUP_BASE) * u, i));
      if (!fill(ring)) return null;
      convexify(ring);
      RING.push(ring);
    }
    return {TOP, RING};
  };
  const near = [];
  for (let c = 0; c < CELLS / 2; c++) {
    const row = [];
    for (let i = 0; i < BINS; i++) row.push(radiusNear((c + .5) / CELLS, i));
    if (fill(row)) convexify(row);
    near.push(row);
  }
  const around = (f, a) => {
    const w = Math.atan2(Math.sin(a), Math.cos(a)), fb = (w + Math.PI) / (2 * Math.PI) * BINS - .5, fc = clamp(f * CELLS - .5, 0, near.length - 1);
    const lo = ((Math.floor(fb) % BINS) + BINS) % BINS, hi = (lo + 1) % BINS, c0 = Math.trunc(fc), c1 = Math.min(near.length - 1, c0 + 1), t = fb - Math.floor(fb), q = fc - c0;
    const r0 = near[c0][lo] + (near[c0][hi] - near[c0][lo]) * t, r1 = near[c1][lo] + (near[c1][hi] - near[c1][lo]) * t;
    return r0 + (r1 - r0) * q;
  };
  return {h, minY, cx, cz, radiusAt: radiusNear, rings, around};
}

// ring point at a continuous angle (CareWearRenderer.ringVertex)
function ringPoint(S, o, a, k, lift, u, v) {
  const {TOP, RING} = o, w = Math.atan2(Math.sin(a), Math.cos(a)), f = (w + Math.PI) / (2 * Math.PI) * BINS - .5;
  const lo = ((Math.floor(f) % BINS) + BINS) % BINS, hi = (lo + 1) % BINS, t = f - Math.floor(f);
  const r = RING[k][lo] + (RING[k][hi] - RING[k][lo]) * t + lift, top = TOP[lo] + (TOP[hi] - TOP[lo]) * t;
  return [S.cx + Math.cos(w) * r, S.minY + (CUP_BASE + (top - CUP_BASE) * k / (RINGS - 1)) * S.h, S.cz + Math.sin(w) * r, u, v];
}

// quads: {tex, tint, n:[x,y,z], p:[[x,y,z,u,v] x4]}
function cloth(S) {
  const out = [], {h, minY, cx, cz} = S, o = S.rings(CUP_FRONT, CUP_BACK);
  if (!o) return out;
  const {TOP, RING} = o, inset = .035 * h * .6, ARC = [0];
  for (let i = 0; i < BINS; i++) {
    const j = (i + 1) % BINS, r0 = RING[2][i] + inset, r1 = RING[2][j] + inset, a0 = angle(i), a1 = angle(j);
    ARC.push(ARC[i] + Math.hypot(Math.cos(a1) * r1 - Math.cos(a0) * r0, Math.sin(a1) * r1 - Math.sin(a0) * r0));
  }
  const tiles = Math.max(1, Math.round(ARC[BINS] * 16 * WOOL_DENSITY / 16)), uScale = tiles / Math.max(1e-4, ARC[BINS]), vScale = WOOL_DENSITY;
  const roll = .016 * h, rise = .012 * h, inward = .03 * h, hem = .03 * h;
  const P = (a, r, y, u, v) => [cx + Math.cos(a) * r, y, cz + Math.sin(a) * r, u, v];
  for (let i = 0; i < BINS; i++) {
    const j = (i + 1) % BINS, a0 = angle(i), a1 = angle(j), am = (a0 + a1) / 2, n = [Math.cos(am), 0, Math.sin(am)];
    const u0 = ARC[i] * uScale, u1 = ARC[i + 1] * uScale;
    for (let k = 0; k + 1 < RINGS; k++) {
      const ua = k / (RINGS - 1), ub = (k + 1) / (RINGS - 1), y = (t, u) => minY + (CUP_BASE + (TOP[t] - CUP_BASE) * u) * h;
      out.push({tex: 'wool', tint: 'cloth', n, p: [P(a0, RING[k][i] + inset, y(i, ua), u0, -(y(i, ua) - minY) * vScale), P(a1, RING[k][j] + inset, y(j, ua), u1, -(y(j, ua) - minY) * vScale),
        P(a1, RING[k + 1][j] + inset, y(j, ub), u1, -(y(j, ub) - minY) * vScale), P(a0, RING[k + 1][i] + inset, y(i, ub), u0, -(y(i, ub) - minY) * vScale)]});
    }
    const top0 = RING[RINGS - 1][i] + inset, top1 = RING[RINGS - 1][j] + inset, yt0 = minY + TOP[i] * h, yt1 = minY + TOP[j] * h;
    const b0 = top0 + roll * .5, b1 = top1 + roll * .5, e = .002 * h;
    out.push({tex: 'wool', tint: 'hem', n, p: [P(a0, b0 + e, yt0 - roll - hem, u0, .5), P(a1, b1 + e, yt1 - roll - hem, u1, .5), P(a1, b1 + e, yt1 - roll, u1, .6), P(a0, b0 + e, yt0 - roll, u0, .6)]});
    out.push({tex: 'wool', tint: 'cloth', n, p: [P(a0, b0, yt0 - roll, u0, 0), P(a1, b1, yt1 - roll, u1, 0), P(a1, b1, yt1, u1, .2), P(a0, b0, yt0, u0, .2)]});
    out.push({tex: 'wool', tint: 'fold', n: [n[0] * .5, .85, n[2] * .5], p: [P(a0, b0, yt0, u0, .2), P(a1, b1, yt1, u1, .2), P(a1, top1 - inward, yt1 + rise, u1, .3), P(a0, top0 - inward, yt0 + rise, u0, .3)]});
  }
  const lift = inset + .014 * h, edge = .07, lead = k => SEAM_AT + SEAM_SWEEP * (1 - SEAM_SLANT * k / (RINGS - 1));
  for (let k = 0; k + 1 < RINGS; k++) {
    const la = lead(k), lb = lead(k + 1);
    for (let s = 0; s < 4; s++) {
      const a0 = SEAM_AT + (la - edge - SEAM_AT) * s / 4, a1 = SEAM_AT + (la - edge - SEAM_AT) * (s + 1) / 4;
      const c0 = SEAM_AT + (lb - edge - SEAM_AT) * s / 4, c1 = SEAM_AT + (lb - edge - SEAM_AT) * (s + 1) / 4, am = (a0 + a1 + c0 + c1) / 4;
      out.push({tex: 'wool', tint: 'cloth', n: [Math.cos(am), 0, Math.sin(am)], p: [ringPoint(S, o, a0, k, lift, s * .4, -k * .25), ringPoint(S, o, a1, k, lift, (s + 1) * .4, -k * .25),
        ringPoint(S, o, c1, k + 1, lift, (s + 1) * .4, -(k + 1) * .25), ringPoint(S, o, c0, k + 1, lift, s * .4, -(k + 1) * .25)]});
    }
    const hl = lift + .003 * h;
    out.push({tex: 'wool', tint: 'hem', n: [Math.cos(la), 0, Math.sin(la)], p: [ringPoint(S, o, la - edge, k, hl, 0, .5), ringPoint(S, o, la, k, hl, .2, .5),
      ringPoint(S, o, lb, k + 1, hl, .2, .6), ringPoint(S, o, lb - edge, k + 1, hl, 0, .6)]});
  }
  return out;
}

function leafWrap(S, soil) {
  const out = [], {h, minY, cx, cz} = S, off = .035 * h;
  const around = S.around;
  const tipLift = t => { const q = clamp((t - .7) / .3, 0, 1); return TIP_LIFT * q * q * (3 - 2 * q); };
  const soilHeight = soil <= 0 ? -1 : .02 + .06 * Math.min(3, soil);
  for (const [stemA, stemF, tipA, tipF, width, layer, row] of LEAVES) {
    const s0 = stemA * ARC_SCALE, ds = tipA * ARC_SCALE - s0, dp = tipF - stemF, length = Math.hypot(ds, dp), base = off * .6 + layer * LAYER * h;
    let ns = -dp / length, np = ds / length;
    if (np < 0) { ns = -ns; np = -np; }
    const grid = [];
    let ok = true;
    for (let i = 0; i <= LEAF_STEPS && ok; i++) for (let j = 0; j <= ACROSS; j++) {
      const t = i / LEAF_STEPS, c = j / ACROSS, a = (s0 + ds * t + ns * (c - .5) * width) / ARC_SCALE;
      let f = stemF + dp * t + np * (c - .5) * width;
      if (Math.abs(Math.atan2(Math.sin(a + Math.PI / 2), Math.cos(a + Math.PI / 2))) < GUARD_HALF) f = Math.min(f, MOUTH_GUARD);
      f = Math.max(CUP_BASE, f);
      let r = around(f, a);
      if (r <= 0) { ok = false; break; }
      r += base + tipLift(t) * h;
      grid[i * (ACROSS + 1) + j] = [cx + Math.cos(a) * r, minY + f * h, cz + Math.sin(a) * r, f];
    }
    if (!ok) continue;
    for (let i = 0; i < LEAF_STEPS; i++) for (let j = 0; j < ACROSS; j++) {
      const g = (ii, jj) => grid[ii * (ACROSS + 1) + jj], c00 = g(i, j), c10 = g(i + 1, j), c11 = g(i + 1, j + 1), c01 = g(i, j + 1);
      const mx = (c00[0] + c11[0]) / 2 - cx, mz = (c00[2] + c11[2]) / 2 - cz, ml = Math.hypot(mx, mz) + 1e-6;
      const cell = row + ((c00[3] + c11[3]) / 2 < soilHeight ? STAINED_V : 0);
      const u0 = i / LEAF_STEPS, u1 = (i + 1) / LEAF_STEPS, v0 = cell + CELL_V * (1 - j / ACROSS), v1 = cell + CELL_V * (1 - (j + 1) / ACROSS);
      out.push({tex: 'leaf', blotch: soil >= 3 && (c00[3] + c11[3]) / 2 < .05, n: [mx / ml, 0, mz / ml],
        p: [[...c00.slice(0, 3), u0, v0], [...c10.slice(0, 3), u1, v0], [...c11.slice(0, 3), u1, v1], [...c01.slice(0, 3), u0, v1]]});
    }
  }
  return out;
}

function groundLeaves(S) {
  const out = [], {h, cx, cz} = S;
  let reach = 0;
  for (let i = 0; i < BINS; i++) reach = Math.max(reach, S.radiusAt(.2, i));
  if (reach <= 0) return out;
  const length = reach * 1.35, width = reach * .75, y = .004 * h;
  GROUND.forEach(([a, row], n) => {
    const lay = y + n * .002 * h, dx = Math.cos(a), dz = Math.sin(a), px = -dz, pz = dx;
    out.push({tex: 'leaf', stain: 0, n: [0, 1, 0], p: [[cx - dx * length - px * width, lay, cz - dz * length - pz * width, 0, row + CELL_V],
      [cx + dx * length - px * width, lay, cz + dz * length - pz * width, 1, row + CELL_V], [cx + dx * length + px * width, lay, cz + dz * length + pz * width, 1, row],
      [cx - dx * length + px * width, lay, cz - dz * length + pz * width, 0, row]]});
  });
  return out;
}

// ---- scene assembly ---------------------------------------------------------------------------------------------------
const atlas = tasks.atlas; // {size, file, tiles:{name:[x,y,w,h,repeatU,repeatV,vShift]}}
const atlasRaw = fs.readFileSync(atlas.file);
const poses = new Map();
function poseFor(id) {
  if (!poses.has(id)) { const model = JSON.parse(fs.readFileSync(path.join(assets, 'yukkuri', id + '.json'), 'utf8')); poses.set(id, new RuntimePose(model, 1, motions[id], fits[id] || null)); }
  return poses.get(id);
}
function tileUV(name, u, v) {
  const [x, y, w, hgt, ru, rv, shift] = atlas.tiles[name];
  return [(x + clamp(u / ru, 0, .9999) * w) / atlas.size, (y + clamp((v + shift) / rv, 0, .9999) * hgt) / atlas.size];
}
function emitQuad(out, q, scale, at, yaw) {
  let name = q.tex === 'wool' ? 'wool_' + q.tint : 'leaf';
  if (q.blotch) name = 'leaf_blotch';
  const c = Math.cos(yaw), s = Math.sin(yaw), place = (x, y, z) => [(x * c + z * s) * scale + at[0], y * scale + at[1], (-x * s + z * c) * scale + at[2]];
  const n = [q.n[0] * c + q.n[2] * s, q.n[1], -q.n[0] * s + q.n[2] * c];
  const v = q.p.map(([x, y, z, u, vv]) => [...place(x * 16, y * 16, z * 16), ...tileUV(name, u, vv)]);
  for (const [tri, sign] of [[[0, 1, 2, 0, 2, 3], 1], [[0, 2, 1, 0, 3, 2], -1]]) // drawn without culling in game: both windings
    for (const k of tri) out.push(...v[k], n[0] * sign, n[1] * sign, n[2] * sign);
}
function actorVertices(actor) {
  const pose = poseFor(actor.model), duration = pose.clips[actor.action].duration;
  pose.expression(actor.face || 'neutral');
  const raw = pose.frame(actor.action, duration * (actor.phase ?? 0)).slice(), model = pose.model;
  const pts = [], hull = [];
  for (const part of model.parts) for (let i = part.start; i < part.start + part.count; i++) {
    const point = [raw[i * 8] / 16, raw[i * 8 + 1] / 16, raw[i * 8 + 2] / 16];
    if (BODY(part.name)) pts.push(...point);
    if (HULL(part.name)) hull.push(...point);
  }
  const scale = actor.scale ?? 1, at = actor.at || [0, 0, 0], yaw = (actor.yaw || 0) * Math.PI / 180, c = Math.cos(yaw), s = Math.sin(yaw);
  const [ex, ey, ew] = atlas.tiles['entity:' + actor.model + ':' + (actor.face || 'neutral')];
  const out = [];
  for (let i = 0; i < raw.length; i += 8) {
    const x = raw[i], z = raw[i + 2], nx = raw[i + 5], nz = raw[i + 7];
    out.push((x * c + z * s) * scale + at[0], raw[i + 1] * scale + at[1], (-x * s + z * c) * scale + at[2],
      (ex + raw[i + 3] * ew) / atlas.size, (ey + raw[i + 4] * ew) / atlas.size, nx * c + nz * s, raw[i + 6], -nx * s + nz * c);
  }
  if (actor.wear) {
    const S = shellFor(pts, hull);
    // CareWearRenderer: the cloth profile is the body alone; the leaf wrap's also takes the hanging back hair
    const quads = actor.wear === 'cloth' ? cloth(shellFor(pts, pts)) : actor.wear === 'open' ? groundLeaves(S) : leafWrap(S, actor.soil || 0);
    for (const q of quads) emitQuad(out, q, scale, at, yaw);
  }
  return out;
}
const result = [];
for (const panel of tasks.panels) {
  const all = panel.actors.flatMap(actorVertices), vertices = Float64Array.from(all);
  const pixels = raster.renderModel(vertices, atlasRaw, tasks.width, tasks.height, panel.azimuth, panel.elevation ?? .12, panel.span ?? 11,
    {centerY: panel.centerY ?? 7.5, textureSize: atlas.size});
  const file = path.join(tasks.work, panel.id + '.rgba');
  fs.writeFileSync(file, Buffer.from(pixels));
  result.push({id: panel.id, file});
}
fs.writeFileSync(path.join(tasks.work, 'result.json'), JSON.stringify(result));
console.log('Rendered ' + result.length + ' wear panels.');
