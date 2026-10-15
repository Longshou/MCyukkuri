const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const read = ref => JSON.parse(fs.readFileSync(path.join(root, ref), 'utf8'));
const growth = require('../runtime/growth.js');
const stem = require('../runtime/stem-birth.js');
const data = {growth:read('profiles/growth.json'), birth:read('behaviors/stem-birth.json'), stem:read('components/common/stem.json'), crown_y:9.646};

// Every individual curve stays bounded and meets the next stage without a size jump.
for (const stage of data.growth.stages) {
  for (const variation of [0, 0.5, 1]) {
    let previous = 0;
    for (let p = 0; p <= 100; p++) {
      const scale = growth.scaleAt(data.growth, stage.id, p / 100, variation);
      assert(scale >= stage.min_scale && scale <= stage.max_scale + 1e-10);
      assert(scale >= previous); previous = scale;
    }
  }
}
assert.equal(growth.scaleAt(data.growth,'baby',1), growth.scaleAt(data.growth,'juvenile',0));
assert.equal(growth.scaleAt(data.growth,'juvenile',1), growth.scaleAt(data.growth,'adult',0));
assert.throws(()=>growth.scaleAt(data.growth,'unknown',0));
assert.throws(()=>growth.scaleAt(data.growth,'adult',NaN));
assert.throws(()=>stem.create(data,{stage:'juvenile',scale:0.8}));
assert.throws(()=>stem.create(data,{ecology:'house'}));
assert.throws(()=>stem.create(data,{reproductionMode:'egg'}));

for (const count of [1,2,3,4,5]) for (const motherScale of [0.8, 1, 1.2]) {
  for (let t = 0; t <= 28; t += 0.05) {
    const state = stem.sample(data,t,motherScale,count);
    assert.equal(state.children.length,count);
    for (const child of state.children) {
      assert(child.position.every(Number.isFinite));
      assert(child.position[1]>=0);
      const baby=data.growth.stages.find(s=>s.id==='baby');
      assert(child.scale>=baby.min_scale && child.scale<=Math.max(baby.max_scale,data.birth.birth_scale)+1e-9);
      if(child.attached) {
        assert.equal(child.stage,'baby');
        assert(child.sleeping);
        assert(Math.abs(child.position[1]+Math.cos(child.rotation)*data.crown_y*child.scale-child.tip[1])<1e-9);
        assert.equal(child.position[0],0);
        assert.equal(child.origin[0],0);
        assert.equal(child.tip[0],0);
        assert(child.origin[2]<data.stem.anchor[2]*motherScale);
        assert(Math.abs(child.position[2]-Math.sin(child.rotation)*data.crown_y*child.scale-child.tip[2])<1e-9);
        const crown=stem.transform([0,data.crown_y,0,0,0,0,1,0],child.scale,child.position,child.rotation,'x');
        assert(Math.hypot(...crown.slice(0,3).map((v,i)=>v-child.tip[i]))<1e-9);
        assert(Math.abs(Math.hypot(...child.tip.map((v,i)=>v-child.origin[i]))-data.stem.pendant_length_reference*data.stem.reference_units_to_model*motherScale)<1e-9);
      } else assert.equal(child.stage,'juvenile');
    }
  }
  // Detachment preserves position and scale; landing has exactly zero foot height.
  Array.from({length:count},(_,i)=>stem.detachTime(data,i,count)).forEach((t,i)=>{
    const before=stem.sample(data,t-1e-7,motherScale,count).children[i];
    const after=stem.sample(data,t,motherScale,count).children[i];
    assert.equal(before.scale,after.scale);
    assert(Math.hypot(...before.position.map((v,k)=>v-after.position[k]))<1e-5);
    const landed=stem.sample(data,t+data.birth.preview_seconds.fall_duration+1e-9,motherScale,count).children[i];
    assert.equal(landed.position[1],0);assert(landed.landed);assert(!landed.sleeping);
  });
}
// An entire cycle in one update emits each birth once; reload never duplicates it.
const fast=stem.create(data,{}, {count:3});assert.equal(fast.advance(100).events.length,3);
assert.equal(fast.advance(100).events.length,0);
const run=stem.create(data,{}, {count:3});assert.equal(run.advance(20.5).events.length,1);
const saved=JSON.parse(JSON.stringify(run.save()));
const resumed=stem.create(data,saved.mother,{count:saved.count});resumed.restore(saved);
assert.equal(resumed.advance(0).events.length,0);
assert.equal(resumed.advance(8).events.length,2);
assert.equal(resumed.advance(1).events.length,0);
const fine=stem.create(data,{}, {count:3}), events=[];
for(let i=0;i<560;i++)events.push(...fine.advance(0.05).events);
assert.equal(events.length,3);assert.equal(new Set(events.map(e=>e.childId)).size,3);
assert.throws(()=>run.advance(-1));assert.throws(()=>run.restore({...saved,emitted:[]}));
const complete=stem.sample(data,28);assert.equal(complete.stemGrowth,1);assert(complete.children.every(c=>c.landed));
assert.equal(data.birth.planned_root_detachment.status,'recorded_only_not_implemented');
assert.equal(data.stem.pendant_length_reference,0.30);
for(let t=0;t<=1;t+=.05)assert.equal(complete.sourcePoint(stem.pathPoint(data.stem,t))[0],0);
for(const count of [1,2,3,4,5]) {
  let draws=0;
  const random=()=>{draws++;return (count-.5)/5;};
  const pregnancy=stem.create(data,{}, {random});
  assert.equal(pregnancy.state().count,count);assert.equal(draws,1);
  assert.equal(pregnancy.advance(50).events.length,count);assert.equal(pregnancy.advance(1).events.length,0);
  assert.equal(draws,1);
  const snapshot=JSON.parse(JSON.stringify(pregnancy.save()));
  const restored=stem.create(data,snapshot.mother,{count:snapshot.count});restored.restore(snapshot);
  assert.equal(restored.state().count,count);assert.equal(restored.advance(0).events.length,0);
}
for(const count of [0,6,2.5,NaN])assert.throws(()=>stem.sample(data,0,1,count));
// Hakkei U's three sockets stay recorded; the fitted layouts hang the farthest fruit where it hangs its farthest
// (art/props/family-life/fit_stem.py moves the nearer ones outward on a shorter stem).
assert.equal(data.stem.reference_sockets.length,3);
for(const layout of Object.values(data.stem.socket_layouts))assert(Math.hypot(layout[0][0]-data.stem.reference_sockets[0][0],layout[0][1]-data.stem.reference_sockets[0][1])<.03);
assert(!('branches' in data.stem));
const sleep=read('expressions/reimu/sleep.json');
assert(sleep.remove_parts.includes('face_rest_left_eye')&&sleep.remove_parts.includes('face_rest_right_eye'));
assert(stem.sleepFace(sleep).every(Number.isFinite));
console.log('Lifecycle checks passed: all 1–5 counts, once-per-pregnancy random selection, closed sleeping eyes, arched stalk and pendant lengths, continuous stage sizes, attachments, landing, eligibility, events and reload.');
