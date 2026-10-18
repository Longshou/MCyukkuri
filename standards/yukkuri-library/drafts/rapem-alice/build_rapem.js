'use strict';
/* Redraw the 雷普魔 face from the program's れいぽう frame selection:
 *   eye 0 / brow 8 / mouth 30 / blush 0  (adult Alice, *label_0643)
 * The preview keeps the same "open eyes + raised brows + open mouth + no blush"
 * structure but uses the project's standard expression parts:
 *   blank_happy eyes + angry brows + open_smile mouth + neutral blush/tears.
 * This is a redraw, not a copy of the game's pixels. */
const fs = require('fs');
const path = require('path');
const {decode, encode} = require('./png.js');
const root = path.resolve(__dirname, '../../../..');
const src = path.join(root, 'standards/yukkuri-library/assembled/alice/textures');
const base = decode(path.join(src, 'neutral.png'));
const eyes = decode(path.join(src, 'blank_happy.png'));
const brows = decode(path.join(src, 'angry.png'));
const mouth = decode(path.join(src, 'open_smile.png'));
if (base.w !== 256 || base.h !== 256) throw new Error('Unexpected standard atlas size');
const out = {w: base.w, h: base.h, data: Buffer.from(base.data)};
function copy(dst, source, x, y, w, h) {
  for (let yy = 0; yy < h; yy++) for (let xx = 0; xx < w; xx++) {
    const si = ((y + yy) * source.w + (x + xx)) * 4;
    const di = ((y + yy) * dst.w + (x + xx)) * 4;
    for (let k = 0; k < 4; k++) dst.data[di + k] = source.data[si + k];
  }
}
copy(out, eyes, 56, 0, 34, 15);    // both eye regions
copy(out, brows, 100, 0, 20, 15);  // both brow regions
copy(out, mouth, 0, 0, 30, 26);    // mouth / open smile
encode(out.w, out.h, out.data, path.join(__dirname, 'variant', 'rapem.png'));
const sheet = Buffer.alloc(256 * 3 * 80 * 3 * 4); sheet.fill(245);
const scale = 3, section = 80 * scale;
for (let y = 0; y < section; y++) for (let x = 0; x < 256 * scale; x++) {
  const si = ((Math.floor(y / scale)) * out.w + Math.floor(x / scale)) * 4;
  const di = (y * sheet.width + x) * 4;
  sheet[di] = out.data[si]; sheet[di + 1] = out.data[si + 1]; sheet[di + 2] = out.data[si + 2]; sheet[di + 3] = 255;
}
encode(256 * scale, 80 * scale, sheet, path.join(__dirname, 'variant', 'rapem-atlas-preview.png'));
console.log('wrote rapem.png');
