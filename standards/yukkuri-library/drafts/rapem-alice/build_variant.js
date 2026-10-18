'use strict';
/* Build the 雷普魔愛麗絲 expression atlas from existing, already-authored Alice faces.
 * The composition uses only the project's own dazed/blank eyes and drool mouth; no new
 * anatomical geometry is added. Run: node standards/yukkuri-library/drafts/rapem-alice/build_variant.js */
const fs = require('fs');
const path = require('path');
const {decode, encode} = require('./png.js');

const root = path.resolve(__dirname, '../../../..');
const source = path.join(root, 'src/main/resources/assets/mcyukkuri/textures/entity/alice');
const outDir = path.join(__dirname, 'variant');
fs.mkdirSync(outDir, {recursive: true});

function copyRect(dst, src, x, y, w, h) {
  for (let yy = 0; yy < h; yy++) for (let xx = 0; xx < w; xx++) {
    const si = ((y + yy) * src.w + (x + xx)) * 4;
    const di = ((y + yy) * dst.w + (x + xx)) * 4;
    for (let k = 0; k < 4; k++) dst.data[di + k] = src.data[si + k];
  }
}

const neutral = decode(path.join(source, 'neutral.png'));
const dazed = decode(path.join(source, 'dazed.png'));
const drool = decode(path.join(source, 'drool.png'));
if (neutral.w !== 256 || neutral.h !== 256) throw new Error('Unexpected Alice atlas size');

// The face uses a 128x128 atlas. The top 28 rows hold eyes, brows, mouth, blush and tears.
const heat = {w: neutral.w, h: neutral.h, data: Buffer.from(neutral.data)};
copyRect(heat, drool, 0, 0, 256, 56);      // open mouth + drool + blush
copyRect(heat, dazed, 56, 0, 34, 15);      // blank / unfocused eyes
copyRect(heat, dazed, 100, 0, 20, 15);     // calm brows
encode(heat.w, heat.h, heat.data, path.join(outDir, 'heat.png'));

// Alias used by the variant model entry. The face is the same heat state; the feral
// difference is carried by motion, aura and the model metadata, not by new anatomy.
encode(heat.w, heat.h, heat.data, path.join(outDir, 'rapem.png'));

// A readable contact sheet for the source repository / review.
const scale = 3, w = 256 * scale, h = 80 * scale;
const sheet = Buffer.alloc(w * h * 4); sheet.fill(245);
for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
  const si = ((Math.floor(y / scale)) * heat.w + Math.floor(x / scale)) * 4;
  const di = (y * w + x) * 4;
  sheet[di] = heat.data[si]; sheet[di + 1] = heat.data[si + 1];
  sheet[di + 2] = heat.data[si + 2]; sheet[di + 3] = 255;
}
encode(w, h, sheet, path.join(outDir, 'heat-atlas-preview.png'));

const manifest = {
  id: 'alice_rapem',
  display_name: '雷普魔爱丽丝',
  kind: 'state_variant',
  base_model: 'src/main/resources/assets/mcyukkuri/yukkuri/alice.json',
  base_id: 'alice',
  textures: {
    heat: 'standards/yukkuri-library/drafts/rapem-alice/variant/heat.png',
    rapem: 'standards/yukkuri-library/drafts/rapem-alice/variant/rapem.png'
  },
  source_faces: {
    eyes: 'src/main/resources/assets/mcyukkuri/textures/entity/alice/dazed.png',
    mouth_blush_drool: 'src/main/resources/assets/mcyukkuri/textures/entity/alice/drool.png',
    base: 'src/main/resources/assets/mcyukkuri/textures/entity/alice/neutral.png'
  },
  citations: [
    {
      source: 'wiki/hakkei-reference/lore/species/alice/README.md',
      text: '记录了“雷普魔”失控变体，以及野外群体因此惧怕或排斥爱丽丝的写法；不应把失控变体推广为每只爱丽丝的潜在本性。'
    },
    {
      source: '.local/reference/l36-komicolle.txt (L36《饅饅來大講堂》)',
      text: '所有愛麗絲種都可能變成失去理智，只想交配的雷普魔，失去理智的同時身體能力也會變強；雷普魔成了許多森林族群滅亡的原因，愛麗絲種因此處於被歧視和殺害的窘境。'
    }
  ],
  presentation_notes: [
    '本变体是异常状态，不是新种族；普通爱丽丝仍以社交、觅食和结伴为主。',
    '发情演出只表现 wiki/L36 所述的失去理智、繁殖驱力与身体能力增强，不包含交配动作或裸露。',
    '热感表情由项目已有的 dazed 白眼与 drool 流涎嘴部拼合，不新增器官或性化特征。'
  ]
};
fs.writeFileSync(path.join(outDir, 'variant.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log('wrote', outDir);
