/* Sanity-check the generated turntable: the frames must actually differ.
 *
 * A turntable page can look plausible while every frame is identical, so this
 * re-renders sentinel angles with the same runtime and asserts that (a) the
 * orbit changes pixels, (b) elevation changes pixels, (c) the wing_sway and
 * biting states differ from the rest pose, and (d) the emitted HTML carries
 * one grid data-URI per state.
 *
 * Usage: node verify-turntable.cjs
 */
const fs = require('node:fs'), path = require('node:path'), zlib = require('node:zlib');

const HERE = __dirname, LIB = path.resolve(HERE, '../..');
const NAME = 'predator_remilia';
const ASSET = path.join(HERE, 'assembled', NAME);
const PAGE = path.join(HERE, 'previews/current/turntable.html');
const SIZE = 200;
const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));

const runtime = ['motion.js', 'render.js']
  .map(f => fs.readFileSync(path.join(LIB, 'runtime', f), 'utf8')).join('\n');
const mod = { exports: {} };
new Function('module', runtime + '\nmodule.exports={poseVertices,renderModel};')(mod);
const { poseVertices, renderModel } = mod.exports;

// Reuse the turntable's own geometry assembly by importing its helpers would
// mean executing the whole render; replicate the minimal parts instead.
const geo = read(path.join(ASSET, `${NAME}.geo.json`))['minecraft:geometry'][0];
const rigData = read(path.join(ASSET, 'animation-rig.json'));
const textureSize = geo.description.texture_width;
const rigBone = Object.fromEntries(rigData.bones.map(b => [b.name, b]));
const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
const NS = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };

const vertices = [], groups = [];
for (const bone of geo.bones) {
  for (const cube of bone.cubes) {
    groups.push(bone.name);
    const [x, y, z] = cube.origin, X = x + cube.size[0], Y = y + cube.size[1], Z = z + cube.size[2];
    const quads = {
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
      const quad = quads[f].map((p, i) => [...p, cs[i][0] / textureSize, cs[i][1] / textureSize, ...NS[f]]);
      for (const k of [0, 1, 2, 0, 2, 3]) vertices.push(...quad[k]);
    }
  }
}
const rig = {
  bones: geo.bones.map(b => ({ name: b.name, parent: rigBone[b.name].parent ?? null, pivot: b.pivot })),
  groups, clips: rigData.clips, stride_units: rigData.stride_units
};

// Colours are all that matter for a difference test, so pass a flat atlas.
// Render with the real atlas: a flat colour washes out the silhouette so the
// orbit test could not tell a rotated model from an unrotated one.
function inflatePng(file) {
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
        const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c);
        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
      }
      cur[i] = v & 0xff;
    }
  }
  if (channels === 4) return new Uint8ClampedArray(out);
  const rgba = new Uint8ClampedArray(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    const s = i * channels;
    rgba[i * 4] = out[s];
    rgba[i * 4 + 1] = out[channels === 1 ? s : s + 1];
    rgba[i * 4 + 2] = out[channels === 1 ? s : s + 2];
    rgba[i * 4 + 3] = channels === 2 ? out[s + 1] : 255;
  }
  return rgba;
}
const atlas = inflatePng(path.join(ASSET, 'textures', 'neutral.png'));

const CUBE_STRIDE = 36 * 8;
const html = fs.readFileSync(path.join(ASSET, 'index.html'), 'utf8');
const start = html.indexOf('const DATA=') + 'const DATA='.length;
let depth = 0, inString = false, escaped = false, end = -1;
for (let i = start; i < html.length; i++) {
  const c = html[i];
  if (inString) { if (escaped) escaped = false; else if (c === '\\') escaped = true; else if (c === '"') inString = false; continue; }
  if (c === '"') inString = true;
  else if (c === '{' || c === '[') depth++;
  else if (c === '}' || c === ']') { depth--; if (depth === 0) { end = i + 1; break; } }
}
const patches = JSON.parse(html.slice(start, end)).model.expressionPatches;
function posed(clip, time, expression) {
  let v = vertices;
  const set = patches[expression];
  if (set) {
    v = vertices.slice();
    for (const p of set) for (let i = 0; i < CUBE_STRIDE; i++) v[p.index * CUBE_STRIDE + i] = p.vertices[i];
  }
  return poseVertices(v, rig, time, true, false, clip);
}

const SPAN = 30, CENTER = 7.5;
const render = (p, az, el) => renderModel(p, atlas, SIZE, SIZE, az, el, SPAN, { centerY: CENTER, textureSize });
// Compare every channel: alpha alone misses shading-only changes, which is
// exactly what a rotated face or a lifted wing produces.
function diff(a, b) {
  let n = 0;
  for (let i = 0; i < a.length; i += 4) {
    if (Math.abs(a[i] - b[i]) > 8 || Math.abs(a[i + 1] - b[i + 1]) > 8
      || Math.abs(a[i + 2] - b[i + 2]) > 8 || Math.abs(a[i + 3] - b[i + 3]) > 8) n++;
  }
  return n;
}

const idle = posed('idle', 0, 'neutral');
const checks = {}, failures = [];
const record = (name, ok, detail) => { checks[name] = { ok: !!ok, detail }; if (!ok) failures.push(`${name}: ${detail}`); };

const front = render(idle, 0, 0.08);
const side = render(idle, Math.PI / 2, 0.08);
const back = render(idle, Math.PI, 0.08);
const top = render(idle, 0, 0.9);
const total = SIZE * SIZE;
record('orbit changes the image', diff(front, side) > total * 0.05 && diff(front, back) > total * 0.05,
  `front/side ${diff(front, side)}px, front/back ${diff(front, back)}px of ${total}`);
record('elevation changes the image', diff(front, top) > total * 0.05,
  `front/top ${diff(front, top)}px`);

const flap = posed('wing_sway', 0.3, 'neutral');
record('flap state differs from rest', diff(front, render(flap, 0, 0.08)) > total * 0.002,
  `${diff(front, render(flap, 0, 0.08))}px differ`);
// The biting state only exists while a variant mouth expression is authored.
const BITE = path.join(HERE, 'assembled', NAME, 'textures', 'biting.png');
if (fs.existsSync(BITE)) {
  const bite = posed('idle', 0, 'biting');
  record('biting state differs from rest', diff(front, render(bite, 0, 0.08)) > 0,
    `${diff(front, render(bite, 0, 0.08))}px differ`);
} else {
  record('no biting atlas, so the page has no biting state',
    !fs.readFileSync(PAGE, 'utf8').includes("'biting'"), 'page omits the state');
}

// Contact sheets of the same frames the page shows, so the orbit can be
// eyeballed without a browser. Written next to the other current previews.
const SHEET = 150;
let crcTable = null;
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
function writeSheet(file, rows, cols, draw) {
  const w = cols * SHEET, h = rows * SHEET;
  const buf = Buffer.alloc(w * h * 4, 0);
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const px = draw(r, c);
      const src = Buffer.from(px.buffer, px.byteOffset, SHEET * SHEET * 4);
      for (let y = 0; y < SHEET; y++)
        src.copy(buf, ((r * SHEET + y) * w + c * SHEET) * 4, y * SHEET * 4, (y + 1) * SHEET * 4);
    }
  }
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    buf.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.writeFileSync(file, Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
}
const shot = (p, azDeg, elDeg) => renderModel(p, atlas, SHEET, SHEET, azDeg * Math.PI / 180,
  elDeg * Math.PI / 180, SPAN, { centerY: CENTER, textureSize });
const OUTDIR = path.join(HERE, 'previews/current');
const AZ_STEPS = [0, 30, 60, 90, 120, 150];
writeSheet(path.join(OUTDIR, `${NAME}-orbit-azimuth.png`), 3, AZ_STEPS.length,
  (r, c) => shot(idle, AZ_STEPS[c], [-20, 8, 24][r]));
writeSheet(path.join(OUTDIR, `${NAME}-orbit-elevation.png`), 2, 4,
  (r, c) => shot(idle, c * 45, [-24, -8, 12, 32][r]));
writeSheet(path.join(OUTDIR, `${NAME}-orbit-flap.png`), 2, 4,
  (r, c) => shot(r ? flap : idle, c * 45, 8));

const page = fs.readFileSync(PAGE, 'utf8');
const grids = (page.match(/data:image\/png;base64,/g) || []).length;
// Two images per state (a frame grid and a fallback still). The count is
// derived from the model so adding or dropping a state cannot silently pass.
const stateClips = rigData.clips;
const expectedStates = 1 + (stateClips.wing_sway ? 1 : 0) + (stateClips.wing_fold ? 1 : 0)
  + (fs.existsSync(BITE) ? 1 : 0);
record('page embeds one grid + still per state', grids === expectedStates * 2,
  `${grids} embedded images, ${expectedStates} states expected`);
record('page wires every state',
  ['idle', 'flap'].every(id => page.includes(id))
  && (!stateClips.wing_fold || page.includes('fold')),
  `states: ${Object.keys(stateClips).filter(k => k === 'wing_sway' || k === 'wing_fold').join(', ')}`);
// A PNG missing its IHDR colour-type bytes still decodes in Node but is
// rejected by Chrome, which would leave the page blank; assert a full header.
const head = Buffer.from((page.match(/data:image\/png;base64,([A-Za-z0-9+/=]+)/) || [])[1] || '', 'base64')
  .subarray(0, 33);
record('embedded PNG carries a complete IHDR',
  head.length >= 33 && head.toString('ascii', 12, 16) === 'IHDR'
  && head[24] === 8 && head[25] === 6 && head.readUInt32BE(16) > 0,
  `bitDepth ${head[24]}, colourType ${head[25]}, width ${head.readUInt32BE(16)}`);
record('page has drag handlers', page.includes('pointermove') && page.includes('setPointerCapture'),
  'pointer drag + capture');
record('page size stays openable', fs.statSync(PAGE).size < 8 * 1024 * 1024,
  `${(fs.statSync(PAGE).size / 1024 / 1024).toFixed(2)} MB`);

// The page's own script has never been executed in a browser here, so at least
// prove it parses: a syntax error would leave a blank canvas with no other
// symptom. Also confirm every scroll position the page can request lands
// inside the embedded grid, since an out-of-range source rect draws nothing.
const blocks = page.match(/<script>([\s\S]*?)<\/script>/g) || [];
let parsed = true, parseError = null;
for (const block of blocks) {
  const src = block.replace(/^<script>/, '').replace(/<\/script>$/, '');
  try {
    new Function(src);
  } catch (e) {
    parsed = false; parseError = e.message;
  }
}
record('inline page script parses', parsed && blocks.length === 1,
  parseError || `${blocks.length} script block(s)`);
const sizeMatch = page.match(/const FRAMES=(\d+), ELEVS=(\d+), SIZE=(\d+)/);
if (sizeMatch) {
  const [, fr, el, sz] = sizeMatch.map(Number);
  const declared = head.readUInt32BE(16);
  record('scroll range fits the embedded grid',
    fr * sz <= declared && el * sz <= head.readUInt32BE(20),
    `grid ${declared}x${head.readUInt32BE(20)}, needs ${fr * sz}x${el * sz}`);
} else {
  record('scroll range fits the embedded grid', false, 'FRAMES/ELEVS/SIZE not found in page');
}

const report = { model: NAME, at: new Date().toISOString(), checks, failures };
fs.mkdirSync(path.join(HERE, 'reports/current'), { recursive: true });
fs.writeFileSync(path.join(HERE, 'reports/current/turntable-verification.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ ok: failures.length === 0, failures, checks: Object.keys(checks).length }));
process.exitCode = failures.length ? 1 : 0;
