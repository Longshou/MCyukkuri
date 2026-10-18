'use strict';
/* Kept under the original entry-point name. Generates a local atlas inspection
 * sheet from approved expression inputs; does not rewrite the retained drafts. */
const fs = require('fs');
const path = require('path');
const {decode, encode} = require('./png.js');
const root = path.resolve(__dirname, '../../../..');
const src = path.join(root, 'standards/yukkuri-library/assembled/alice/textures');
const out = path.join(root, '.local/previews/rapem-alice');
const faces = ['neutral', 'confused', 'closed_smile', 'angry', 'sleepy', 'surprise'];
const scale = 3, width = 256 * scale, band = 80 * scale, height = band * faces.length;
const sheet = Buffer.alloc(width * height * 4, 245);
for (let face = 0; face < faces.length; face++) {
  const atlas = decode(path.join(src, faces[face] + '.png'));
  if (atlas.w !== 256 || atlas.h !== 256) throw new Error('Unexpected atlas size: ' + faces[face]);
  for (let y = 0; y < band; y++) for (let x = 0; x < width; x++) {
    const si = (Math.floor(y / scale) * atlas.w + Math.floor(x / scale)) * 4;
    const di = ((face * band + y) * width + x) * 4;
    const alpha = atlas.data[si + 3] / 255;
    for (let c = 0; c < 3; c++) sheet[di + c] = atlas.data[si + c] * alpha + 245 * (1 - alpha);
    sheet[di + 3] = 255;
  }
}
fs.mkdirSync(out, {recursive: true});
encode(width, height, sheet, path.join(out, 'expression-atlas.png'));
console.log('wrote expression-atlas.png:', faces.join(', '));
