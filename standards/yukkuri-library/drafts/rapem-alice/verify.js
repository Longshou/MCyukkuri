'use strict';
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../../../..');
const {RuntimePose} = require(path.join(root, 'preview/review/pose.js'));
const {buildHeatClips} = require('./motion-clips.js');
const species = ['alice', 'marisa', 'reimu', 'chen', 'patchouli'];
const requiredFaces = ['flushed_smile', 'flushed_tongue', 'teary_tongue', 'teary_tongue_b'];
let failed = false;
for (const id of species) {
  const model = JSON.parse(fs.readFileSync(path.join(root, 'src/main/resources/assets/mcyukkuri/yukkuri/' + id + '.json'), 'utf8'));
  const pose = new RuntimePose(model, 1, null, null);
  const clips = buildHeatClips(model, {variant: id === 'alice' ? 'alice_rapem' : 'generic'});
  Object.assign(pose.clips, clips);
  let bad = 0;
  for (const [name, clip] of Object.entries(clips)) {
    for (let i = 0; i <= 20; i++) {
      const out = pose.frame(name, clip.duration * i / 20);
      for (let k = 0; k < out.length; k++) if (!Number.isFinite(out[k])) bad++;
    }
  }
  let max = 0;
  for (let step = 0; step <= 20; step++) {
    const t = 7.6 * step / 20;
    const heat = Float64Array.from(pose.frame('heat_onset', t));
    const rapem = Float64Array.from(pose.frame('rapem_onset', t));
    for (let i = 0; i < heat.length; i += 8) {
      max = Math.max(max, Math.hypot(heat[i] - rapem[i], heat[i + 1] - rapem[i + 1], heat[i + 2] - rapem[i + 2]));
    }
  }
  const faceDir = path.join(root, 'standards/yukkuri-library/assembled', id, 'textures');
  const missing = requiredFaces.filter(face => !fs.existsSync(path.join(faceDir, face + '.png')));
  const heatExpr = clips.heat_onset.expression_spans.map(span => span[2]).join(',');
  const rapemExpr = clips.rapem_onset.expression_spans.map(span => span[2]).join(',');
  console.log(id,
    'clips', Object.keys(clips).join(','),
    'bad', bad,
    'heat/rapem pose delta', max.toFixed(2),
    'heat expr', heatExpr,
    'rapem expr', rapemExpr,
    'missing faces', missing.length ? missing.join(',') : 'none');
  if (bad || missing.length) failed = true;
}
if (failed) process.exitCode = 1;
