/* Offline multi-angle preview for the predator draft.
 *
 * Chrome cannot be spawned or attached inside this sandbox (spawn EPERM, CDP
 * handshake timeout), so this renders the *exported* artifacts directly with
 * the library's own rasterizer and skinning code: geo.json gives the bones and
 * cubes, animation-rig.json the clips, and the runtime modules supply
 * poseVertices()/renderModel() unchanged.
 *
 * Usage: node render-views.cjs [modelName] [clip] [time]
 */
const fs = require('node:fs'), path = require('node:path');
const zlib = require('node:zlib');

const HERE = __dirname;
const LIB = path.resolve(HERE, '../..');
const argv = process.argv.slice(2);
const flag = name => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : null; };
const positional = argv.filter((a, i) => !a.startsWith('--') && !(i > 0 && argv[i - 1].startsWith('--')));
const NAME = positional[0] || 'predator_remilia';
// Defaults to this package's own build; pass --asset to compare against another
// export, e.g. --asset ../../assembled/marisa to render the untouched base.
const assetDir = flag('--asset') || path.join(HERE, 'assembled', NAME);
const ASSET = path.isAbsolute(assetDir) ? assetDir : path.resolve(HERE, assetDir);
const OUT = path.join(HERE, 'previews/current');
const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));

// Reuse the library runtime verbatim.
const runtime = ['motion.js', 'render.js']
  .map(f => fs.readFileSync(path.join(LIB, 'runtime', f), 'utf8')).join('\n');
const sandbox = { module: undefined };
const load = new Function('module', runtime + '\nreturn { poseVertices, renderModel, compileMotion };');
const runtimeExports = load(sandbox);
const { poseVertices, renderModel } = runtimeExports;

// ------------------------------------------------------------------ PNG I/O

function readPng(file) {
  const buf = fs.readFileSync(file);
  let pos = 8, width = 0, height = 0, bitDepth = 0, colorType = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos), type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0); height = data.readUInt32BE(4);
      bitDepth = data[8]; colorType = data[9];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (bitDepth !== 8) throw new Error(`unsupported bit depth ${bitDepth}`);
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[colorType];
  if (!channels) throw new Error(`unsupported colour type ${colorType}`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(height * stride);
  let rp = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[rp++];
    const line = raw.subarray(rp, rp + stride); rp += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y ? out.subarray((y - 1) * stride, y * stride) : Buffer.alloc(stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? cur[i - channels] : 0, b = prev[i], c = i >= channels ? prev[i - channels] : 0;
      let v = line[i];
      if (filter === 1) v += a; else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
      }
      cur[i] = v & 0xff;
    }
  }
  return { width, height, channels, data: out };
}

function writePng(file, width, height, rgba) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  const src = Buffer.from(rgba.buffer || rgba, rgba.byteOffset || 0, width * height * 4);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    src.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
  fs.writeFileSync(file, png);
}

let CRC_TABLE = null;
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

// ------------------------------------------------------- rebuild vertex buffer

const geo = read(path.join(ASSET, `${NAME}.geo.json`))['minecraft:geometry'][0];
const rigData = read(path.join(ASSET, 'animation-rig.json'));
const textureSize = geo.description.texture_width;
const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
const NS = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };

function faceQuads(o, s) {
  const [x, y, z] = o, X = x + s[0], Y = y + s[1], Z = z + s[2];
  return {
    north: [[X, Y, z], [x, Y, z], [x, y, z], [X, y, z]],
    south: [[x, Y, Z], [X, Y, Z], [X, y, Z], [x, y, Z]],
    east: [[X, Y, Z], [X, Y, z], [X, y, z], [X, y, Z]],
    west: [[x, Y, z], [x, Y, Z], [x, y, Z], [x, y, z]],
    up: [[x, Y, z], [X, Y, z], [X, Y, Z], [x, Y, Z]],
    down: [[x, y, Z], [X, y, Z], [X, y, z], [x, y, z]]
  };
}

function rotatePoint(p, rotation, pivot) {
  if (!rotation || rotation.every(v => !v)) return p;
  const [ax, ay, az] = rotation.map((v, i) => v * Math.PI / 180 * [-1, -1, 1][i]);
  const [cx, sx, cy, sy, cz, sz] = [Math.cos(ax), Math.sin(ax), Math.cos(ay), Math.sin(ay), Math.cos(az), Math.sin(az)];
  const m = [[cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
             [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
             [-sy, cy * sx, cy * cx]];
  const v = [p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]];
  return m.map((row, i) => row[0] * v[0] + row[1] * v[1] + row[2] * v[2] + pivot[i]);
}

const bones = geo.bones;
const rigBone = Object.fromEntries(rigData.bones.map(b => [b.name, b]));
const vertices = [], groups = [];
// The runtime's compileMotion() resolves each group entry as a bone *name*
// through its own id table, so `groups` mirrors the viewer's format exactly.
for (const bone of bones) {
  if (!rigBone[bone.name]) throw new Error(`geo bone ${bone.name} missing from rig`);
  for (const cube of bone.cubes) {
    groups.push(bone.name);
    const quads = faceQuads(cube.origin, cube.size);
    for (const face of FACES) {
      const uv = cube.uv[face];
      const [u, v] = uv.uv, [uw, uh] = uv.uv_size;
      const corners = [[u, v], [u + uw, v], [u + uw, v + uh], [u, v + uh]];
      const quad = quads[face].map((p, i) => {
        const q = cube.rotation ? rotatePoint(p, cube.rotation, cube.pivot) : p;
        return [...q, corners[i][0] / textureSize, corners[i][1] / textureSize, ...NS[face]];
      });
      for (const idx of [0, 1, 2, 0, 2, 3]) vertices.push(...quad[idx]);
    }
  }
}

// animation-rig.json omits `parent` on roots and orders bones differently from
// geo.json, so rebuild the bone list in geo order, fleshed out from the rig.
const rig = {
  bones: bones.map(b => ({
    name: b.name,
    parent: rigBone[b.name].parent ?? null,
    pivot: b.pivot
  })),
  groups, clips: rigData.clips, stride_units: rigData.stride_units
};

// ------------------------------------------------------------------- rendering

const tex = readPng(path.join(ASSET, 'textures', 'neutral.png'));
if (tex.width !== textureSize) throw new Error(`atlas ${tex.width} != declared ${textureSize}`);
const bitePath = path.join(ASSET, 'textures', 'biting.png');
const hasBiting = fs.existsSync(bitePath);
const biteTex = hasBiting ? readPng(bitePath) : tex;

const VIEWS = [
  ['front', 0, 0.06], ['front-low', 0, -0.22], ['three-quarter', 0.7, 0.14],
  ['side', Math.PI / 2, 0.10], ['back', Math.PI, 0.12], ['top', 0, 0.70],
  ['three-quarter-back', Math.PI * 0.72, 0.18]
];
const CLIPS = (positional[1]
  ? [[positional[1], Number(positional[2] || 0)]]
  : [['idle', 0], ['wing_sway', 0.3], ['waddle', 0.6],
     ...(rig.clips.wing_fold ? [['wing_fold', 1.0]] : [])]
).filter(([c]) => rig.clips[c]);

fs.mkdirSync(OUT, { recursive: true });
const report = { model: NAME, textureSize, cubes: groups.length, bones: bones.length, renders: [] };
const W = 460, H = 460;

if (process.argv.includes('--diag')) {
  const src = fs.readFileSync(path.join(LIB, 'runtime', 'motion.js'), 'utf8');
  const lines = src.split('\n');
  const failing = lines[30];
  console.error('line31 len', failing.length);
  const wrapper = new Function('module', src + '\nmodule.exports={poseVertices,compileMotion};');
  const sandboxModule = { exports: {} };
  wrapper(sandboxModule);
  const { poseVertices: pv, compileMotion: cm } = sandboxModule.exports;
  const c = cm(rig);
  console.error('c.groups[0..3]', c.groups.slice(0, 4), 'len', c.groups.length);
  console.error('ranges len', c.ranges.length, 'verts', vertices.length);
  const bad = [];
  for (let i = 0; i < c.groups.length; i++) if (c.groups[i] === undefined) bad.push(i);
  console.error('undefined groups', bad.length, bad.slice(0, 5));
  const clip = rig.clips.idle;
  const boneNames = new Set(rig.bones.map(b => b.name));
  const trackMissing = Object.keys(clip.tracks).filter(n => !boneNames.has(n));
  console.error('clip tracks not in bones:', trackMissing);
  try {
    const posed = pv(vertices, rig, 0, true, false, 'idle');
    console.error('pose ok finite', posed.every(Number.isFinite));
  } catch (e) {
    const m = /<anonymous>:(\d+):(\d+)/.exec(e.stack || '');
    if (m) {
      const line = failing, col = Number(m[2]);
      console.error('FAIL line', m[1], 'col', col);
      console.error('context:', JSON.stringify(line.slice(Math.max(0, col - 60), col + 30)));
    }
    console.error('err', e.message);
  }
  process.exit(0);
}

// Cubes are 36 floats each; expression patches overwrite a whole cube's block,
// exactly as the viewer does when a non-neutral expression is selected.
const CUBE_STRIDE = 36 * 8;
let modelPatches = null;
function withExpression(expression) {
  if (!modelPatches) {
    // Base64 payloads contain ';' and braces, so scan for the matching brace
    // instead of splitting on the statement terminator.
    const html = fs.readFileSync(path.join(ASSET, 'index.html'), 'utf8');
    const start = html.indexOf('const DATA=') + 'const DATA='.length;
    let depth = 0, inString = false, escaped = false, end = -1;
    for (let i = start; i < html.length; i++) {
      const c = html[i];
      if (inString) {
        if (escaped) escaped = false;
        else if (c === '\\') escaped = true;
        else if (c === '"') inString = false;
        continue;
      }
      if (c === '"') inString = true;
      else if (c === '{' || c === '[') depth++;
      else if (c === '}' || c === ']') { depth--; if (depth === 0) { end = i + 1; break; } }
    }
    if (end < 0) throw new Error('could not locate DATA JSON in index.html');
    modelPatches = JSON.parse(html.slice(start, end)).model.expressionPatches;
  }
  const set = modelPatches[expression];
  if (!set) return vertices;
  const out = vertices.slice();
  for (const p of set)
    for (let i = 0; i < CUBE_STRIDE; i++) out[p.index * CUBE_STRIDE + i] = p.vertices[i];
  return out;
}

// Frame every sampled pose with one camera so views stay comparable.
let lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
for (const [clip, time] of CLIPS) {
  if (!rig.clips[clip]) { console.error('missing clip', clip); continue; }
  const posed = poseVertices(vertices, rig, time, true, false, clip);
  for (let i = 0; i < posed.length; i += 8)
    for (let j = 0; j < 3; j++) {
      lo[j] = Math.min(lo[j], posed[i + j]);
      hi[j] = Math.max(hi[j], posed[i + j]);
    }
}
const CENTER_Y = (lo[1] + hi[1]) / 2;
const DATA_SPAN = Math.max(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]) * 1.28;
report.bounds = { lo, hi, centerY: CENTER_Y, span: DATA_SPAN };

for (const [clip, time] of CLIPS) {
  if (!rig.clips[clip]) { console.error('missing clip', clip); continue; }
  const posed = poseVertices(vertices, rig, time, true, false, clip);
  for (const [label, az, el] of VIEWS) {
    const pixels = renderModel(posed, tex.data, W, H, az, el, DATA_SPAN, { centerY: CENTER_Y, textureSize });
    const file = path.join(OUT, `${NAME}-${clip}-${label}.png`);
    writePng(file, W, H, Buffer.from(pixels.buffer));
    report.renders.push({ clip, time, view: label, az, el, file: path.basename(file) });
  }
}

// Face close-ups: the fangs and the variant's own mouth are too small to judge
// at full-body framing, so the head is rendered tight with the biting atlas.
const headY = (lo[1] + hi[1]) / 2 + (hi[1] - lo[1]) * 0.24;
const headSpan = DATA_SPAN * 0.34;
const facePasses = hasBiting
  ? [['face-neutral', 0, 0.02, tex, 'neutral'],
     ['face-biting', 0, 0.02, biteTex, 'biting'],
     ['face-biting-low', 0, -0.30, biteTex, 'biting']]
  : [['face-neutral', 0, 0.02, tex, 'neutral'], ['face-low', 0, -0.30, tex, 'neutral']];
for (const [label, az, el, texture, expression] of facePasses) {
  const posed = poseVertices(withExpression(expression), rig, 0, true, false, 'idle');
  const pixels = renderModel(posed, texture.data, W, H, az, el, headSpan, { centerY: headY, textureSize });
  const file = path.join(OUT, `${NAME}-${label}.png`);
  writePng(file, W, H, Buffer.from(pixels.buffer));
  report.renders.push({ clip: 'idle', time: 0, view: label, az, el, file: path.basename(file), closeup: true });
}

// Contact sheet for quick review.
const sheetW = W * 4, sheetH = H * 2;
const sheet = Buffer.alloc(sheetW * sheetH * 4, 0);
report.renders.filter(r => !r.closeup).slice(0, 8).forEach((r, i) => {
  const img = readPng(path.join(OUT, r.file));
  const ox = (i % 4) * W, oy = Math.floor(i / 4) * H;
  for (let y = 0; y < H; y++)
    img.data.copy(sheet, ((oy + y) * sheetW + ox) * 4, y * W * 4, (y + 1) * W * 4);
});
writePng(path.join(OUT, `${NAME}-sheet.png`), sheetW, sheetH, sheet);

// Wing-only sheet: isolates the new geometry so its silhouette can be judged
// without the body and head occluding it. Exports without a wing chain (such as
// the base model, rendered for comparison) skip straight past it.
const wingCubes = rig.groups.map((g, i) => ({ g, i })).filter(x => x.g.startsWith('wing_'));
const wingSet = new Set(wingCubes.map(x => x.i));
if (wingSet.size) {
  const wingW = W * 3, wingH = H * 2;
  const wingSheet = Buffer.alloc(wingW * wingH * 4, 0);
  [['front', 0, 0.05], ['side', Math.PI / 2, 0.05], ['top', 0, 0.75],
   ['back', Math.PI, 0.05], ['three-quarter', 0.7, 0.10], ['wing_sway', 0, 0.05]]
    .forEach(([label, az, el], i) => {
    const clip = label === 'wing_sway' ? 'wing_sway' : 'idle';
    const posed = poseVertices(withExpression('neutral'), rig, label === 'wing_sway' ? 0.3 : 0, true, false, clip);
    const solo = posed.map((v, k) => {
      const cube = Math.floor(k / (36 * 8));
      return wingSet.has(cube) ? v : 0;
    });
    // Zeroed vertices collapse to the origin and rasterize as invisible;
    // shift them well outside the frame instead.
    for (let k = 0; k < solo.length; k += 8) {
      const cube = Math.floor(k / (36 * 8));
      if (!wingSet.has(cube)) solo[k + 1] = -9999;
    }
    const pixels = renderModel(solo, tex.data, W, H, az, el, DATA_SPAN, { centerY: CENTER_Y, textureSize });
    const file = path.join(OUT, `${NAME}-wings-${label}.png`);
    writePng(file, W, H, Buffer.from(pixels.buffer));
    report.renders.push({ clip, time: 0, view: `wings-${label}`, az, el, file: path.basename(file), closeup: true });
    const img = readPng(file);
    const ox = (i % 3) * W, oy = Math.floor(i / 3) * H;
    for (let y = 0; y < H; y++)
      img.data.copy(wingSheet, ((oy + y) * wingW + ox) * 4, y * W * 4, (y + 1) * W * 4);
  });
  writePng(path.join(OUT, `${NAME}-wing-sheet.png`), wingW, wingH, wingSheet);
}

// The close-up sheet is only meaningful when there is a biting expression to
// compare against; otherwise the base's own neutral head stands alone.
// Part-isolation sheet: renders one bone group alone so a feature that is
// hidden behind the body can still be judged. Used for the enlarged face.
const faceGroups = ['face_eye_left', 'face_eye_right', 'face_brow_left', 'face_brow_right',
                    'face_mouth', 'face_blush_left', 'face_blush_right'];
const faceSet = new Set(rig.groups.map((g, i) => ({ g, i })).filter(x => faceGroups.includes(x.g)).map(x => x.i));
if (faceSet.size) {
  const posed = poseVertices(withExpression('neutral'), rig, 0, true, false, 'idle');
  const solo = posed.slice();
  for (let k = 0; k < solo.length; k += 8)
    if (!faceSet.has(Math.floor(k / (36 * 8)))) solo[k + 1] = -9999;
  const pixels = renderModel(solo, tex.data, W, H, 0, 0.02, headSpan, { centerY: headY, textureSize });
  const file = path.join(OUT, `${NAME}-face-isolated.png`);
  writePng(file, W, H, Buffer.from(pixels.buffer));
  report.renders.push({ clip: 'idle', time: 0, view: 'face-isolated', az: 0, el: 0.02,
                        file: path.basename(file), closeup: true });
  console.log('face-isolated cubes:', faceSet.size);
}

const faceW = W * 3, faceH = H;
const faceSheet = Buffer.alloc(faceW * faceH * 4, 0);
report.renders.filter(r => r.closeup && r.view.startsWith('face')).forEach((r, i) => {
  const img = readPng(path.join(OUT, r.file));
  const ox = i * W;
  for (let y = 0; y < H; y++)
    img.data.copy(faceSheet, (y * faceW + ox) * 4, y * W * 4, (y + 1) * W * 4);
});
writePng(path.join(OUT, `${NAME}-face-sheet.png`), faceW, faceH, faceSheet);

fs.writeFileSync(path.join(OUT, 'render-report.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ model: NAME, renders: report.renders.length, sheet: `${NAME}-sheet.png` }));
