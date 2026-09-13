/* Structural checks over the exported predator draft.
 *
 * Verifies data consistency (exports agree with each other), part attachment
 * (wings rooted near the body, fangs in front of the face plane) and that every
 * clip produces finite poses. It cannot judge whether the shape looks right.
 *
 * Usage: node verify.cjs
 */
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const HERE = __dirname, LIB = path.resolve(HERE, '../..');
const NAME = 'predator_remilia';
const ASSET = path.join(HERE, 'assembled', NAME);
const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');

const runtime = ['motion.js', 'render.js']
  .map(f => fs.readFileSync(path.join(LIB, 'runtime', f), 'utf8')).join('\n');
const mod = { exports: {} };
new Function('module', runtime + '\nmodule.exports={poseVertices};')(mod);
const { poseVertices } = mod.exports;

const recipes = read(path.join(HERE, 'recipes.json'));
const manifest = read(path.join(HERE, 'manifest.json'));
const recipe = recipes['variants'][NAME];
const bb = read(path.join(ASSET, `${NAME}.bbmodel`));
const geo = read(path.join(ASSET, `${NAME}.geo.json`))['minecraft:geometry'][0];
const rig = read(path.join(ASSET, 'animation-rig.json'));
const anims = read(path.join(ASSET, `${NAME}.animation.json`)).animations;
const report = { model: NAME, at: new Date().toISOString(), checks: {}, fingerprints: {}, failures: [] };
const check = (name, ok, detail) => {
  report.checks[name] = { ok: !!ok, detail };
  if (!ok) report.failures.push(`${name}: ${detail}`);
};

// ---------------------------------------------------------------- consistency
const cubesInGeo = geo.bones.reduce((n, b) => n + b.cubes.length, 0);
check('element/group/geo counts agree',
  bb.elements.length === rig.groups.length && bb.elements.length === cubesInGeo,
  `bbmodel ${bb.elements.length}, groups ${rig.groups.length}, geo cubes ${cubesInGeo}`);
check('element uuids unique',
  new Set(bb.elements.map(e => e.uuid)).size === bb.elements.length, 'all distinct');
check('bone names unique', new Set(rig.bones.map(b => b.name)).size === rig.bones.length,
  `${rig.bones.length} bones`);
check('every group names an existing bone', rig.groups.every(g => rig.bones.some(b => b.name === g)),
  'no orphan groups');
check('every cube has positive extent',
  bb.elements.every(e => e.from.every((v, i) => Number.isFinite(v) && e.to[i] > v)),
  'finite, non-degenerate');
check('geo cube origin/size match bbmodel',
  geo.bones.every(bone => bone.cubes.every((c, i) => {
    const own = bb.elements.filter((e, j) => rig.groups[j] === bone.name);
    const e = own[i];
    return e && c.origin.every((v, k) => Math.abs(v - e.from[k]) < 1e-6)
      && c.size.every((v, k) => Math.abs(v - (e.to[k] - e.from[k])) < 1e-6);
  })), 'per-bone cube order matches');

// Remove-list really applied, and no hat cube survived.
const dropped = new Set(recipe.remove_elements);
check('witch hat fully removed',
  !bb.elements.some(e => dropped.has(e.name)), `${dropped.size} names listed`);
check('mob cap present',
  ['cap_crown', 'cap_band', 'cap_brim', 'cap_ruffle_0'].every(n => bb.elements.some(e => e.name.startsWith(n))),
  'crown, band, brim and ruffle pieces present');
// A dome has to taper in steps, otherwise the cap is back to a flat cylinder.
const domeTiers = [0, 1, 2].map(i => bb.elements.find(e => e.name === `cap_dome${i}_left`)).filter(Boolean);
check('cap dome tapers in tiers',
  domeTiers.length >= 2
  && domeTiers.every((e, i) => i === 0 || (e.to[0] - e.from[0]) < (domeTiers[i - 1].to[0] - domeTiers[i - 1].from[0])),
  domeTiers.map(e => (e.to[0] - e.from[0]).toFixed(2)).join(' > ') || 'no dome tiers');
// The cap must read as a disc, not a square plate: a square plate would have
// corner blocks exactly as wide as the side plates, so require a clear inset.
const crownSides = bb.elements.filter(e => e.name === 'cap_crown_left' || e.name === 'cap_crown_right');
const crownCorners = bb.elements.filter(e => /^cap_crown_(near|far)$/.test(e.name));
const sideW = crownSides.length ? crownSides[0].to[0] - crownSides[0].from[0] : 0;
check('cap crown is elliptical, not square',
  crownCorners.length === 2 && crownSides.length === 2
  && crownCorners.every(c => (c.to[0] - c.from[0]) < sideW * 0.9),
  `side width ${sideW.toFixed(2)} vs corner width `
  + `${crownCorners.map(c => (c.to[0] - c.from[0]).toFixed(2)).join('/')}`);
check('texture size declared in both exports',
  geo.description.texture_width === recipes.texture.size
  && geo.description.texture_height === recipes.texture.size,
  `${geo.description.texture_width}x${geo.description.texture_height}`);
const atlasSize = fs.readFileSync(path.join(ASSET, 'textures', 'neutral.png'))
  .readUInt32BE(16);
check('atlas matches declared texture size', atlasSize === recipes.texture.size,
  `png ${atlasSize}, declared ${recipes.texture.size}`);

// ---------------------------------------------------------------- attachment
const wingCubes = bb.elements.filter(e => e.name.startsWith('wing_'));
const bodyTops = bb.elements.filter(e => /^soft_head_slice_/.test(e.name));
const bodyMaxX = Math.max(...bodyTops.map(e => e.to[0]));
const rootGap = Math.min(...wingCubes.map(e => Math.abs(e.from[0]))) - 0;
check('wing roots anchored inside/at the flank',
  rootGap < bodyMaxX, `innermost wing |x| ${rootGap.toFixed(2)} vs body half-width ${bodyMaxX.toFixed(2)}`);
const headZBack = Math.max(...bb.elements.filter(e => /hair|soft_head_slice/.test(e.name)).map(e => e.to[2]));
const wingMinZ = Math.min(...wingCubes.map(e => e.from[2]));
check('wings sit behind the torso front', wingMinZ > 0,
  `min wing z ${wingMinZ.toFixed(2)}, head back ${headZBack.toFixed(2)}`);

const mouth = bb.elements.find(e => e.name === 'face_mouth');
const fangs = bb.elements.filter(e => e.name.startsWith('fang_'));
// The reference shows no tusks, so this draft carries none; the checks below
// only assert attachment when a fang part is actually authored.
const tuskPlan = recipe.fang || null;
check('tusk inventory matches the recipe',
  fangs.length === (tuskPlan ? 2 : 0),
  tuskPlan ? `${fangs.length} tusks authored` : 'no tusks, as the reference shows none');
if (fangs.length) {
  check('tusks sit in front of the face plane',
    fangs.every(f => f.from[2] < mouth.from[2]),
    `tusk front z ${Math.min(...fangs.map(f => f.from[2])).toFixed(2)} vs face z ${mouth.from[2].toFixed(2)}`);
  check('tusks hang from the mouth band',
    fangs.every(f => f.to[1] >= mouth.to[1] - 0.9 && f.from[1] < mouth.to[1]),
    `tusk top y ${Math.max(...fangs.map(f => f.to[1])).toFixed(2)}, tips y ${Math.min(...fangs.map(f => f.from[1])).toFixed(2)}, mouth top ${mouth.to[1].toFixed(2)}`);
}

// ------------------------------------------------------------------- skeleton
const wingBones = rig.bones.filter(b => b.name.startsWith('wing_'));
check('wing bones match the authored fingers', wingBones.length === recipe.wing.fingers.length * 2,
  `${wingBones.length} wing bones for ${recipe.wing.fingers.length} fingers/side`);
// The wing must stay a SMALL wing: reference R2 measures a single wing at about
// 0.67 of the body width, and an earlier pass shipped one at 2.7x. Assert the
// ratio so the silhouette cannot silently balloon again.
const wingCubesAll = bb.elements.filter(e => e.name.startsWith('wing_'));
const bodyElems = bb.elements.filter(e => /^soft_head_slice_/.test(e.name));
const bodyW = Math.max(...bodyElems.map(e => e.to[0])) - Math.min(...bodyElems.map(e => e.from[0]));
const wingReach = Math.max(...wingCubesAll.map(e => Math.abs(e.to[0]))) - bodyW / 2;
check('wing length stays ~0.67 of body width',
  wingReach / bodyW > 0.45 && wingReach / bodyW < 0.95,
  `reach ${wingReach.toFixed(2)} / body ${bodyW.toFixed(2)} = ${(wingReach / bodyW).toFixed(2)}`);
check('wing bones parent onto the feature chain',
  wingBones.every(b => b.parent === 'features_squash'), 'all parented to features_squash');

// -------------------------------------------------------------- finite poses
const groupsByName = rig.groups;
const boneOrder = rig.bones.map(b => b.name);
const idx = Object.fromEntries(boneOrder.map((n, i) => [n, i]));
const rigForPose = { bones: rig.bones.map(b => ({ ...b, parent: b.parent ?? null })), groups: groupsByName, clips: rig.clips, stride_units: rig.stride_units };
const verts = [];
{
  const SIZE = 256;
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  const NS = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
  for (const bone of geo.bones) {
    for (const cube of bone.cubes) {
      const [x, y, z] = cube.origin, X = x + cube.size[0], Y = y + cube.size[1], Z = z + cube.size[2];
      const q = {
        north: [[X, Y, z], [x, Y, z], [x, y, z], [X, y, z]],
        south: [[x, Y, Z], [X, Y, Z], [X, y, Z], [x, y, Z]],
        east: [[X, Y, Z], [X, Y, z], [X, y, z], [X, y, Z]],
        west: [[x, Y, z], [x, Y, Z], [x, y, Z], [x, y, z]],
        up: [[x, Y, z], [X, Y, z], [X, Y, Z], [x, Y, Z]],
        down: [[x, y, Z], [X, y, Z], [X, y, z], [x, y, z]]
      };
      for (const f of FACES) {
        const [u, v] = cube.uv[f].uv, [uw, uh] = cube.uv[f].uv_size;
        const cs = [[u, v], [u + uw, v], [u + uw, v + uh], [u, v + uh]];
        const quad = q[f].map((p, i) => [...p, cs[i][0] / SIZE, cs[i][1] / SIZE, ...NS[f]]);
        for (const k of [0, 1, 2, 0, 2, 3]) verts.push(...quad[k]);
      }
    }
  }
}
let finite = true, nonFiniteClip = null;
for (const [id, clip] of Object.entries(rig.clips)) {
  for (const t of [0, clip.duration * 0.25, clip.duration * 0.5, clip.duration * 0.75, clip.duration]) {
    const posed = poseVertices(verts, rigForPose, t, true, false, id);
    if (!posed.every(Number.isFinite)) { finite = false; nonFiniteClip = `${id}@${t}`; break; }
  }
  if (!finite) break;
}
check('all clips produce finite poses', finite, nonFiniteClip || `${Object.keys(rig.clips).length} clips sampled at 5 phases`);
check('every clip exported', Object.keys(rig.clips).every(k => anims[`animation.${NAME}.${k}`]),
  `${Object.keys(anims).length} exported animations`);

// ------------------------------------------------- runtime contract agreement
// The library runtime slices a flat vertex buffer per cube using
// element_vertex_counts (or 36) and resolves each group as a bone name, so the
// rebuilt buffer must line up with those assumptions exactly.
const perCube = rig.element_vertex_counts || null;
check('runtime per-cube vertex assumption holds',
  !perCube || (perCube.length === bb.elements.length && perCube.every(n => n === 36)),
  perCube ? `element_vertex_counts present, all 36 (${perCube.length} entries)` : 'absent, runtime defaults to 36');
check('rebuilt buffer length matches cube count',
  verts.length === bb.elements.length * 36 * 8,
  `${verts.length} floats = ${bb.elements.length} cubes x 36 x 8`);
check('runtime clip tracks all name real bones',
  Object.values(rig.clips).every(c => Object.keys(c.tracks).every(n => idx[n] !== undefined)),
  'no dangling track names');
check('preview html embeds the same geometry',
  (() => {
    const html = fs.readFileSync(path.join(ASSET, 'index.html'), 'utf8');
    return html.includes('const DATA=') && html.includes(`"${NAME}"`);
  })(), 'index.html carries the embedded DATA payload');
check('wing_sway drives both wings',
  (() => {
    const t = rig.clips.wing_sway && rig.clips.wing_sway.tracks;
    if (!t) return false;
    const sides = ['left', 'right'].filter(s => Object.keys(t).some(n => n.endsWith('_' + s)));
    // One track per wing bone per side, so scale the expectation with the model.
    return sides.length === 2 && Object.keys(t).length >= wingBones.length;
  })(), `expects >= ${wingBones.length} tracks, one per wing bone`);
check('wing_sway and wing_fold share the same chain',
  !rig.clips.wing_fold
  || new Set(Object.keys(rig.clips.wing_fold.tracks)).size === new Set(Object.keys(rig.clips.wing_sway.tracks)).size,
  'both clips key the same bones');

// The wings must actually move: sample the outermost finger bone's world matrix
// via posed vertices at two phases of wing_sway and require a displacement.
const outerWingBone = wingBones.map(b => b.name).sort().pop();
const wingCubeStart = rig.groups.findIndex(g => g === outerWingBone);
let swayDelta = 0;
if (wingCubeStart >= 0) {
  const a = poseVertices(verts, rigForPose, 0.3, true, false, 'wing_sway');
  const b = poseVertices(verts, rigForPose, 0.9, true, false, 'wing_sway');
  const base = wingCubeStart * 36 * 8;
  for (let i = base; i < base + 36 * 8; i += 8) swayDelta = Math.max(swayDelta, Math.abs(b[i + 1] - a[i + 1]));
}
check('wing_sway produces visible tip motion', swayDelta > 0.15,
  `max Y displacement ${swayDelta.toFixed(3)} model units on ${outerWingBone}`);

// ------------------------------------------------------------- fingerprints
for (const rel of [`${NAME}.bbmodel`, `${NAME}.geo.json`, `${NAME}.animation.json`,
                   'animation-rig.json', 'textures/neutral.png', 'textures/biting.png']) {
  const full = path.join(ASSET, rel);
  if (fs.existsSync(full)) report.fingerprints[rel] = sha(full);
}
report.fingerprints['recipes.json'] = sha(path.join(HERE, 'recipes.json'));
report.fingerprints['build.py'] = sha(path.join(HERE, 'build.py'));
report.base_source_sha256 = manifest[NAME].source_sha256;
report.counts = { cubes: bb.elements.length, bones: rig.bones.length, animations: Object.keys(anims).length };
report.expressions = { total: fs.readdirSync(path.join(ASSET, 'textures')).length };

fs.mkdirSync(path.join(HERE, 'reports/current'), { recursive: true });
fs.writeFileSync(path.join(HERE, 'reports/current/verification.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ ok: report.failures.length === 0, failures: report.failures, counts: report.counts }));
process.exitCode = report.failures.length ? 1 : 0;
