'use strict';
// Existing non-compiling resource inspection. This does not validate gameplay.
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../../../..');
const {RuntimePose} = require(path.join(root, 'preview/review/pose.js'));
const {buildHeatClips} = require('./motion-clips.js');
const species = ['alice', 'marisa', 'reimu', 'chen', 'patchouli'];
let failed = false;
for (const id of species) {
  const model = JSON.parse(fs.readFileSync(path.join(root, 'src/main/resources/assets/mcyukkuri/yukkuri/' + id + '.json'), 'utf8'));
  const pose = new RuntimePose(model, 1, null, null);
  const clips = buildHeatClips(model, {variant: id === 'alice' ? 'alice_rapem' : 'generic'});
  Object.assign(pose.clips, clips);
  let bad = 0;
  const missing = [];
  for (const [name, clip] of Object.entries(clips)) {
    if ((clip.loop !== (name === 'heat_onset' || name === 'rapem_act')) || !clip.presentation.adult_only || name.startsWith('anatomy_')) bad++;
    for (const span of clip.expression_spans) {
      const file = ['alice_agitated','breeding_period','state_recover'].includes(span[2]) ? path.join(__dirname, 'variant', span[2] + '.png') : path.join(root, 'standards/yukkuri-library/assembled', id, 'textures', span[2] + '.png');
      if (!fs.existsSync(file)) missing.push(span[2]);
    }
    for (let i = 0; i <= 20; i++) {
      const out = pose.frame(name, clip.duration * i / 20);
      for (let k = 0; k < out.length; k++) if (!Number.isFinite(out[k])) bad++;
    }
  }
  let delta = 0;
  if (id === 'alice') {
    for (let step = 0; step <= 20; step++) {
      const t = clips.heat_onset.duration * step / 20;
      const heat = Float64Array.from(pose.frame('heat_onset', t));
      const rapem = Float64Array.from(pose.frame('rapem_onset', t));
      for (let i = 0; i < heat.length; i += 8) {
        delta = Math.max(delta, Math.hypot(heat[i] - rapem[i], heat[i + 1] - rapem[i + 1], heat[i + 2] - rapem[i + 2]));
      }
    }
  }
  console.log(id, 'clips', Object.keys(clips).join(','), 'bad', bad,
    'branch pose difference', id === 'alice' ? delta.toFixed(2) : 'Alice-only',
    'faces', [...new Set(Object.values(clips).flatMap(c => c.expression_spans.map(s => s[2])))].join(','),
    'missing', missing.length ? missing.join(',') : 'none');
  if (bad || missing.length) failed = true;
}
if (failed) process.exitCode = 1;
