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
for (const name of ['neutral', 'confused', 'closed_smile', 'angry', 'sleepy', 'surprise']) {
  textures[name] = decode(path.join(texDir, name + '.png'));
}
function visible(out) {
  const chunks = [];
  for (const part of pose.model.parts) {
    if (part.anatomy || /^(peni_taper|mamu_)/.test(part.name)) continue;
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
  ['heat_onset', 0.5, '01 NORMAL'],
  ['heat_onset', 2.7, '02 UNEASY'],
  ['heat_onset', 4.8, '03 RELIEF'],
  ['heat_onset', 7.6, '04 TIRED'],
  ['heat_onset', 10.0, '05 CALM'],
  ['rapem_onset', 0.5, '01 ALERT'],
  ['rapem_onset', 4.1, '02 APPROACH'],
  ['rapem_onset', 6.0, '03 INTERRUPTED'],
  ['rapem_onset', 7.6, '04 TIRED'],
  ['rapem_onset', 10.0, '05 CALM']
];
const W = 340, H = 336, header = 74, rowTitle = 34, footer = 38, cols = 5, rows = 2;
const width = W * cols, height = header + (H + rowTitle) * rows + footer;
const sheet = Buffer.alloc(width * height * 4);
for (let i = 0; i < sheet.length; i += 4) {
  sheet[i] = 12; sheet[i + 1] = 24; sheet[i + 2] = 24; sheet[i + 3] = 255;
}
const font = {
 A:'01110/10001/10001/11111/10001/10001/10001',B:'11110/10001/10001/11110/10001/10001/11110',
 C:'01111/10000/10000/10000/10000/10000/01111',D:'11110/10001/10001/10001/10001/10001/11110',
 E:'11111/10000/10000/11110/10000/10000/11111',F:'11111/10000/10000/11110/10000/10000/10000',
 G:'01111/10000/10000/10111/10001/10001/01111',H:'10001/10001/10001/11111/10001/10001/10001',
 I:'11111/00100/00100/00100/00100/00100/11111',J:'00111/00010/00010/00010/10010/10010/01100',
 K:'10001/10010/10100/11000/10100/10010/10001',L:'10000/10000/10000/10000/10000/10000/11111',
 M:'10001/11011/10101/10101/10001/10001/10001',N:'10001/11001/10101/10011/10001/10001/10001',
 O:'01110/10001/10001/10001/10001/10001/01110',P:'11110/10001/10001/11110/10000/10000/10000',
 Q:'01110/10001/10001/10001/10101/10010/01101',R:'11110/10001/10001/11110/10100/10010/10001',
 S:'01111/10000/10000/01110/00001/00001/11110',T:'11111/00100/00100/00100/00100/00100/00100',
 U:'10001/10001/10001/10001/10001/10001/01110',V:'10001/10001/10001/10001/10001/01010/00100',
 W:'10001/10001/10001/10101/10101/10101/01010',X:'10001/10001/01010/00100/01010/10001/10001',
 Y:'10001/10001/01010/00100/00100/00100/00100',Z:'11111/00001/00010/00100/01000/10000/11111',
 0:'01110/10001/10011/10101/11001/10001/01110',1:'00100/01100/00100/00100/00100/00100/01110',
 2:'01110/10001/00001/00010/00100/01000/11111',3:'11110/00001/00001/01110/00001/00001/11110',
 4:'00010/00110/01010/10010/11111/00010/00010',5:'11111/10000/10000/11110/00001/00001/11110',
 6:'01110/10000/10000/11110/10001/10001/01110',7:'11111/00001/00010/00100/01000/01000/01000',
 8:'01110/10001/10001/01110/10001/10001/01110',9:'01110/10001/10001/01111/00001/00001/01110',
 '/':'00001/00010/00010/00100/01000/01000/10000','-':'00000/00000/00000/11111/00000/00000/00000',
 '.':'00000/00000/00000/00000/00000/00100/00100','_':'00000/00000/00000/00000/00000/00000/11111'
};
function label(text, x, y, scale = 2, color = [213, 231, 216]) {
  for (const ch of text.toUpperCase()) {
    const rows = font[ch]?.split('/') || [];
    for (let yy = 0; yy < rows.length; yy++) for (let xx = 0; xx < 5; xx++) {
      if (rows[yy][xx] !== '1') continue;
      for (let sy = 0; sy < scale; sy++) for (let sx = 0; sx < scale; sx++) {
        const px = x + xx * scale + sx, py = y + yy * scale + sy;
        if (px < 0 || py < 0 || px >= width || py >= height) continue;
        const index = (py * width + px) * 4;
        sheet[index] = color[0]; sheet[index + 1] = color[1]; sheet[index + 2] = color[2];
      }
    }
    x += scale * 6;
  }
}
label('ADULT EXPRESSION / MOTION CANDIDATES', 22, 18, 3);
label('STANDARD FACES - FINITE EPISODES - PENDING APPROVAL - NOT IN-GAME', 22, 49, 2, [153, 185, 173]);
label('A / BRIEF RELIEF', 22, header + 9, 2, [159, 217, 183]);
label('B / ALICE ABNORMAL STATE - INTERRUPTION EXAMPLE', 22, header + rowTitle + H + 9, 2, [229, 180, 143]);
for (let cell = 0; cell < cells.length; cell++) {
  const [clip, t, title] = cells[cell];
  const expression = pose.expressionFor(clip, t, 'neutral');
  const vertices = visible(pose.frame(clip, t));
  const pixels = raster.renderModel(vertices, textures[expression].data, W, H, 0.28, 0.12, 13.0, {centerY: 4.8, textureSize: 256});
  const ox = (cell % cols) * W, oy = header + rowTitle + Math.floor(cell / cols) * (H + rowTitle);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const i = (y * W + x) * 4, di = ((oy + y) * width + ox + x) * 4;
    const glow = Math.max(0, 1 - Math.hypot((x - W / 2) / W * 1.55, (y - H * 0.43) / H * 1.35) * 1.6);
    const shadow = Math.exp(-(((x - W / 2) / (W * 0.28)) ** 2 + ((y - H * 0.675) / (H * 0.045)) ** 2)) * 0.55;
    const color = cell < 5 ? [12 + 19 * glow, 24 + 30 * glow, 24 + 23 * glow] : [22 + 28 * glow, 24 + 21 * glow, 23 + 17 * glow];
    const a = pixels[i + 3] / 255;
    for (let c = 0; c < 3; c++) sheet[di + c] = color[c] * (1 - shadow) * (1 - a) + pixels[i + c] * a;
    sheet[di + 3] = 255;
  }
  label(title, ox + 18, oy + 18);
  label(expression + ' / ' + t.toFixed(1) + 'S', ox + 18, oy + H - 29, 2, [176, 196, 185]);
}
label('FACES ARE SHARED WITH THE GAME. TIMING / CHOREOGRAPHY ARE AUTHORING CHOICES.', 22, height - 26, 2, [153, 185, 173]);
const out = path.join(root, '.local/previews/rapem-alice');
fs.mkdirSync(out, {recursive: true});
encode(width, height, sheet, path.join(out, 'contact.png'));
console.log('wrote contact.png');
