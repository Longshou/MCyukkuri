'use strict';
/* Offline contact sheet for the two presentations, using the runtime model, the
 * standard assembled expression atlases and the authored runtime motion clips. */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const {decode, encode} = require('./png.js');
const root = path.resolve(__dirname, '../../../..');
const {RuntimePose} = require(path.join(root, 'preview/review/pose.js'));
const {buildHeatClips} = require('./motion-clips.js');
const raster = {};
vm.runInNewContext(fs.readFileSync(path.join(root, 'standards/yukkuri-library/runtime/render.js'), 'utf8'), raster);
const model = JSON.parse(fs.readFileSync(path.join(root, 'src/main/resources/assets/mcyukkuri/yukkuri/alice.json'), 'utf8'));
const pose = new RuntimePose(model, 1, null, null);
Object.assign(pose.clips, buildHeatClips(model, {variant: 'alice_rapem'}));
const texDir = path.join(root, 'standards/yukkuri-library/assembled/alice/textures');
const textures = {};
for (const name of ['surprise', 'flushed_smile', 'flushed_tongue', 'teary_tongue']) {
  textures[name] = decode(path.join(texDir, name + '.png'));
}
textures.rapem = decode(path.join(__dirname, 'variant', 'rapem.png'));
textures.sukkiri_tongue = decode(path.join(__dirname, 'variant', 'sukkiri_tongue.png'));
function visible(out) {
  const chunks = [];
  for (const part of pose.model.parts) {
    if (part.anatomy) continue;
    for (let i = part.start; i < part.start + part.count; i++) {
      const o = i * 8;
      chunks.push(out.subarray(o, o + 8));
    }
  }
  const v = new Float32Array(chunks.length * 8);
  chunks.forEach((c, i) => v.set(c, i * 8));
  let min = Infinity;
  for (let i = 0; i < v.length; i += 8) min = Math.min(min, v[i + 1]);
  const lift = min < 0.02 ? 0.02 - min : 0;
  if (lift) for (let i = 1; i < v.length; i += 8) v[i] += lift;
  return v;
}
const cells = [
  ['heat_onset', 0.2, 'surprise', false],
  ['heat_onset', 1.5, 'flushed_smile', false],
  ['heat_onset', 3.5, 'sukkiri_tongue', false],
  ['heat_onset', 6.0, 'sukkiri_tongue', false],
  ['rapem_onset', 0.2, 'surprise', true],
  ['rapem_onset', 0.8, 'rapem', true],
  ['rapem_onset', 2.5, 'rapem', true],
  ['rapem_onset', 4.0, 'rapem', true]
];
const W = 420, H = 420, cols = 4, rows = 2;
const sheet = Buffer.alloc(W * cols * H * rows * 4);
for (let cell = 0; cell < cells.length; cell++) {
  const [clip, t, expression, feral] = cells[cell];
  const vertices = visible(pose.frame(clip, t));
  const pixels = raster.renderModel(vertices, textures[expression].data, W, H, 0.28, 0.12, 13.8, {centerY: 4.8, textureSize: 256});
  const ox = (cell % cols) * W, oy = Math.floor(cell / cols) * H;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const i = (y * W + x) * 4;
    const di = ((oy + y) * W * cols + ox + x) * 4;
    const dx = (x - W / 2) / W, dy = (y - H * 0.43) / H;
    const glow = Math.max(0, 1 - Math.hypot(dx * 1.55, dy * 1.35) * 1.6);
    let r = 9 + 30 * glow, g = 16 + 40 * glow, b = 14 + 32 * glow;
    if (feral) {
      const fg = Math.max(0, 1 - Math.hypot(dx * 1.3, dy * 1.1) * 1.8);
      r += 38 * fg; g += 8 * fg; b += 4 * fg;
    }
    const sx = (x - W / 2) / (W * 0.28), sy = (y - H * 0.675) / (H * 0.045);
    const shadow = Math.exp(-(sx * sx + sy * sy)) * 0.55;
    r *= 1 - shadow; g *= 1 - shadow; b *= 1 - shadow;
    const a = pixels[i + 3] / 255;
    if (a > 0) {
      r = r * (1 - a) + pixels[i] * a;
      g = g * (1 - a) + pixels[i + 1] * a;
      b = b * (1 - a) + pixels[i + 2] * a;
    }
    sheet[di] = r; sheet[di + 1] = g; sheet[di + 2] = b; sheet[di + 3] = 255;
  }
}
encode(W * cols, H * rows, sheet, path.join(root, '.local/previews/rapem-alice/contact.png'));
console.log('wrote contact');
