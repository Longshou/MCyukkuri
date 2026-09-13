/* Offline turntable: renders an orbit of the exported model and wraps the
 * frames into one self-contained HTML page with drag-to-rotate.
 *
 * Chrome cannot run inside this sandbox (Node spawn EPERM, CDP handshake
 * timeout), so instead of driving the library's interactive viewer this
 * pre-renders a (azimuth x elevation) frame grid with the library's own
 * rasterizer and lets the page scrub it. Geometry, rig and atlas are the
 * exported artifacts, so what you rotate is the real model.
 *
 * Usage: node render-turntable.cjs [modelName] [--asset relative/dir]
 *        [--frames 24] [--elev 4] [--size 400]
 *        [--out previews/current/turntable.html]
 *
 * Three states ship in one page: the rest pose, a mid-flap pose (so the new
 * wing articulation can be turned and inspected) and the biting expression.
 */
const fs = require('node:fs'), path = require('node:path'), zlib = require('node:zlib');

const HERE = __dirname;
const LIB = path.resolve(HERE, '../..');
const argv = process.argv.slice(2);
const flag = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : fallback;
};
const positional = argv.filter((a, i) => !a.startsWith('--') && !(i > 0 && argv[i - 1].startsWith('--')));

const NAME = positional[0] || 'predator_remilia';
const FRAMES = Number(flag('--frames', 24));
const ELEVS = Number(flag('--elev', 4));
const SIZE = Number(flag('--size', 400));
const assetDir = flag('--asset') || path.join(HERE, 'assembled', NAME);
const ASSET = path.isAbsolute(assetDir) ? assetDir : path.resolve(HERE, assetDir);
const OUT = path.resolve(HERE, flag('--out', 'previews/current/turntable.html'));
const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));

// ------------------------------------------------- reuse the library runtime
const runtime = ['motion.js', 'render.js']
  .map(f => fs.readFileSync(path.join(LIB, 'runtime', f), 'utf8')).join('\n');
const mod = { exports: {} };
new Function('module', runtime + '\nmodule.exports={poseVertices,renderModel};')(mod);
const { poseVertices, renderModel } = mod.exports;

// -------------------------------------------------------------- PNG helpers
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

let CRC = null;
function crc32(buf) {
  if (!CRC) {
    CRC = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function pngBuffer(width, height, rgba) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  const src = Buffer.from(rgba.buffer || rgba, rgba.byteOffset || 0, width * height * 4);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    src.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
  // bit depth 8, colour type 6 (RGBA); without these a browser refuses the image.
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

function writePng(file, width, height, rgba) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, pngBuffer(width, height, rgba));
}

// --------------------------------------------------- rebuild the vertex buffer
const geo = read(path.join(ASSET, `${NAME}.geo.json`))['minecraft:geometry'][0];
const rigData = read(path.join(ASSET, 'animation-rig.json'));
const textureSize = geo.description.texture_width;
const rigBone = Object.fromEntries(rigData.bones.map(b => [b.name, b]));
const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
const NS = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };

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
const vertices = [], groups = [];
for (const bone of bones) {
  if (!rigBone[bone.name]) throw new Error(`geo bone ${bone.name} missing from rig`);
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
      const corners = [[u, v], [u + uw, v], [u + uw, v + uh], [u, v + uh]];
      const quad = quads[f].map((p, i) => {
        const q = cube.rotation ? rotatePoint(p, cube.rotation, cube.pivot) : p;
        return [...q, corners[i][0] / textureSize, corners[i][1] / textureSize, ...NS[f]];
      });
      for (const k of [0, 1, 2, 0, 2, 3]) vertices.push(...quad[k]);
    }
  }
}

const rig = {
  bones: bones.map(b => ({ name: b.name, parent: rigBone[b.name].parent ?? null, pivot: b.pivot })),
  groups, clips: rigData.clips, stride_units: rigData.stride_units
};

// Expression patches, applied exactly as the viewer does.
const CUBE_STRIDE = 36 * 8;
let patches = null;
function withExpression(expression) {
  if (!patches) {
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
    patches = JSON.parse(html.slice(start, end)).model.expressionPatches;
  }
  const set = patches[expression];
  if (!set) return vertices;
  const out = vertices.slice();
  for (const p of set)
    for (let i = 0; i < CUBE_STRIDE; i++) out[p.index * CUBE_STRIDE + i] = p.vertices[i];
  return out;
}

const tex = readPng(path.join(ASSET, 'textures', 'neutral.png'));
if (tex.width !== textureSize) throw new Error(`atlas ${tex.width} != declared ${textureSize}`);
const bitePath = path.join(ASSET, 'textures', 'biting.png');
const biteTex = fs.existsSync(bitePath) ? readPng(bitePath) : tex;

// --------------------------------------------------------------- frame grids
const ELEV_DEG = [-20, -6, 8, 24].slice(0, ELEVS);
const wingClip = rig.clips.wing_sway ? 'wing_sway' : 'idle';
const STATES = [
  { id: 'idle', label: '静止（展翼）', clip: 'idle', time: 0, expression: 'neutral', texture: tex },
  wingClip === 'wing_sway'
    ? { id: 'flap', label: '振翅中', clip: 'wing_sway', time: 0.3, expression: 'neutral', texture: tex }
    : null,
  // R3 shows a tucked rest pose, so offer it alongside the spread posture.
  rig.clips.wing_fold
    ? { id: 'fold', label: '收翼', clip: 'wing_fold', time: 1.0, expression: 'neutral', texture: tex }
    : null,
  fs.existsSync(bitePath)
    ? { id: 'biting', label: '张口露牙', clip: 'idle', time: 0, expression: 'biting', texture: biteTex }
    : null
].filter(Boolean);

// One camera for every state so switching does not jump the framing.
let lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
const posedByState = {};
for (const st of STATES) {
  if (!rig.clips[st.clip]) throw new Error(`clip ${st.clip} not in ${Object.keys(rig.clips).join(', ')}`);
  const posed = poseVertices(withExpression(st.expression), rig, st.time, true, false, st.clip);
  posedByState[st.id] = posed;
  for (let i = 0; i < posed.length; i += 8)
    for (let j = 0; j < 3; j++) {
      lo[j] = Math.min(lo[j], posed[i + j]);
      hi[j] = Math.max(hi[j], posed[i + j]);
    }
}
const CENTER_Y = (lo[1] + hi[1]) / 2;
const SPAN = Math.max(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]) * 1.22;

const gridW = FRAMES * SIZE, gridH = ELEV_DEG.length * SIZE;
const grids = {};
for (const st of STATES) {
  const posed = posedByState[st.id];
  const grid = Buffer.alloc(gridW * gridH * 4, 0);
  for (let r = 0; r < ELEV_DEG.length; r++) {
    const el = ELEV_DEG[r] * Math.PI / 180;
    for (let c = 0; c < FRAMES; c++) {
      const az = (c / FRAMES) * Math.PI * 2;
      const px = renderModel(posed, st.texture.data, SIZE, SIZE, az, el, SPAN, { centerY: CENTER_Y, textureSize });
      const src = Buffer.from(px.buffer, px.byteOffset, SIZE * SIZE * 4);
      for (let y = 0; y < SIZE; y++)
        src.copy(grid, ((r * SIZE + y) * gridW + c * SIZE) * 4, y * SIZE * 4, (y + 1) * SIZE * 4);
    }
  }
  grids[st.id] = pngBuffer(gridW, gridH, grid);
  process.stdout.write(`${st.id} grid done\n`);
}

const still = {};
for (const st of STATES) {
  still[st.id] = pngBuffer(SIZE, SIZE, Buffer.from(renderModel(posedByState[st.id], st.texture.data,
    SIZE, SIZE, 0, 0.08, SPAN, { centerY: CENTER_Y, textureSize }).buffer));
}

const label = (() => {
  try {
    return read(path.join(HERE, 'recipes.json')).variants[NAME].label;
  } catch {
    return NAME;
  }
})();
const dataUri = b => 'data:image/png;base64,' + b.toString('base64');
const stateData = STATES.map(st => ({
  id: st.id, label: st.label, clip: st.clip, time: st.time, expression: st.expression,
  grid: dataUri(grids[st.id]), still: dataUri(still[st.id])
}));
const html = `<!doctype html>
<html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${label} · 转台预览</title>
<style>
  *{box-sizing:border-box}
  body{margin:0;background:#eae7de;color:#51463e;font:15px system-ui}
  main{max-width:880px;margin:auto;padding:18px}
  h1{font-size:20px;margin:0 0 6px}
  p{margin:6px 0;line-height:1.6}
  .stage{position:relative;background:linear-gradient(#eae7de 86%,#dcd6c8);border-radius:14px;overflow:hidden;
         cursor:grab;touch-action:none;user-select:none}
  .stage.dragging{cursor:grabbing}
  canvas{width:100%;height:auto;display:block}
  .hint{position:absolute;left:10px;bottom:8px;font-size:12px;color:#7b7163;background:#eae7dec9;
        padding:2px 7px;border-radius:6px}
  .controls{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:10px 0}
  button{background:#fff6e4;border:1px solid #b8aa91;border-radius:8px;padding:7px 11px;color:inherit;cursor:pointer}
  button:hover{background:#fffdf7}
  button.on{background:#e5d9bf;border-color:#8f7f63;font-weight:600}
  input[type=range]{flex:1;min-width:140px;accent-color:#857258}
  .readout{font-variant-numeric:tabular-nums;color:#796f60;font-size:13px;min-width:132px}
  .note{font-size:13px;color:#796f60}
</style>
<main>
  <h1>${label}</h1>
  <p>在图上按住拖动即可任意转动视角：左右拖转方位，上下拖调俯仰。也可以用下面的滑杆、方向和状态按钮。</p>
  <div class="stage" id="stage"><canvas id="view" width="${SIZE}" height="${SIZE}"></canvas>
    <span class="hint">拖动旋转 · 双击复位</span></div>
  <div class="controls" id="states"></div>
  <div class="controls">
    <input type="range" id="az" min="0" max="${FRAMES - 1}" step="1" value="0" aria-label="方位">
    <span class="readout" id="readout"></span>
  </div>
  <div class="controls">
    <button data-az="0">正面</button><button data-az="${Math.round(FRAMES / 4)}">右侧</button>
    <button data-az="${Math.round(FRAMES / 2)}">背面</button><button data-az="${Math.round(FRAMES * 3 / 4)}">左侧</button>
    <button id="spin">开始自转</button>
  </div>
  <p class="note">离线渲染的转台帧（${FRAMES} 个方位 × ${ELEV_DEG.length} 个俯仰 × ${STATES.length} 个状态），
  用素材库自身的 <code>runtime/render.js</code> 对导出后的几何、骨架与图集渲染，不经过浏览器 3D 引擎。</p>
</main>
<script>
const FRAMES=${FRAMES}, ELEVS=${ELEV_DEG.length}, SIZE=${SIZE}, ELEV_DEG=${JSON.stringify(ELEV_DEG)};
const STATES=${JSON.stringify(stateData)};
const canvas=document.getElementById('view'), ctx=canvas.getContext('2d');
const images=STATES.map(s=>{
  const g=new Image(), st=new Image();
  g.src=s.grid; st.src=s.still;
  return {grid:g, still:st, ready:false, meta:s};
});
images.forEach(o=>{o.grid.onload=()=>{o.ready=true;draw()}});
let current=0, az=0, el=1, spin=false, dragging=null;
function draw(){
  const o=images[current];
  ctx.clearRect(0,0,SIZE,SIZE);
  if(o.ready)ctx.drawImage(o.grid,(az%FRAMES)*SIZE,el*SIZE,SIZE,SIZE,0,0,SIZE,SIZE);
  else ctx.drawImage(o.still,0,0,SIZE,SIZE);
  document.getElementById('az').value=az;
  document.getElementById('readout').textContent=
    '方位 '+Math.round(az*360/FRAMES)+'° · 俯仰 '+ELEV_DEG[el]+'°';
  document.querySelectorAll('#states button').forEach((b,i)=>b.classList.toggle('on',i===current));
}
const stateBar=document.getElementById('states');
STATES.forEach((s,i)=>{
  const b=document.createElement('button');
  b.textContent=s.label;
  b.onclick=()=>{current=i;draw()};
  stateBar.append(b);
});
const stage=document.getElementById('stage');
stage.addEventListener('pointerdown',e=>{
  dragging={x:e.clientX,y:e.clientY,az,el};stage.classList.add('dragging');stage.setPointerCapture(e.pointerId);
});
stage.addEventListener('pointermove',e=>{
  if(!dragging)return;
  const scale=FRAMES/360;
  az=((dragging.az+Math.round((e.clientX-dragging.x)*scale))%FRAMES+FRAMES)%FRAMES;
  const dEl=Math.round((dragging.y-e.clientY)/90);
  el=Math.max(0,Math.min(ELEVS-1,dragging.el+dEl));
  draw();
});
const end=()=>{dragging=null;stage.classList.remove('dragging')};
stage.addEventListener('pointerup',end);stage.addEventListener('pointercancel',end);
stage.addEventListener('dblclick',()=>{az=0;el=1;draw()});
document.getElementById('az').addEventListener('input',e=>{az=+e.target.value;draw()});
document.querySelectorAll('[data-az]').forEach(b=>b.onclick=()=>{az=+b.dataset.az;draw()});
document.getElementById('spin').onclick=e=>{
  spin=!spin;e.target.textContent=spin?'停止自转':'开始自转';
  if(spin){last=0;requestAnimationFrame(tick)}
};
let last=0;
function tick(now){
  if(!spin)return;
  if(!last||now-last>70){az=(az+1)%FRAMES;draw();last=now}
  requestAnimationFrame(tick);
}
draw();
</script>
</html>
`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, html);
console.log(JSON.stringify({
  model: NAME, states: STATES.map(s => `${s.id}(${s.clip}@${s.time},${s.expression})`),
  framesPerState: FRAMES * ELEV_DEG.length, grid: `${gridW}x${gridH}`,
  out: path.relative(HERE, OUT), bytes: fs.statSync(OUT).size
}, null, 1));
