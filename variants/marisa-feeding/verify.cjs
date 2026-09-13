const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');
const {Feeding}=require('./feeding.js');
const {poseVertices}=require('./motion.js');
function advance(s,n,dt=1/60){for(let t=0;t<n-1e-8;t+=dt)s.update(Math.min(dt,n-t));}
let s=new Feeding();s.start();advance(s,10);assert.equal(s.bites,0);assert.equal(s.z,13);
s.fill();advance(s,4.4);assert.equal(s.state,'bite');assert.equal(s.food,8);advance(s,.3);assert.equal(s.food,7);assert.equal(s.fullness,0);advance(s,1.9);assert.equal(s.fullness,1);
advance(s,30);assert.equal(s.state,'full');assert.equal(s.food,0);assert.equal(s.fullness,8);assert.equal(s.bites,8);assert.equal(s.events.filter(x=>x.type==='take').length,8);assert.equal(s.events.filter(x=>x.type==='swallow').length,8);
for(const dt of [1/24,1/60,1/144,.37]){s=new Feeding();s.fill();s.start();advance(s,30,dt);assert.equal(s.food,0);assert.equal(s.bites,8);assert.equal(s.state,'full');}
s=new Feeding();s.fill();s.start();advance(s,4.2);s.empty();advance(s,3);assert.equal(s.fullness,0);assert.equal(s.mouthful,false);assert.equal(s.state,'waiting');s.fill();advance(s,3);assert.equal(s.bites,1);
s=new Feeding();s.fill();s.start();advance(s,4.7);assert.equal(s.mouthful,true);s.empty();advance(s,3);assert.equal(s.fullness,1);assert.equal(s.state,'waiting');
s=new Feeding();s.fill();s.start();advance(s,1);s.empty();advance(s,3);assert.equal(s.state,'waiting');assert(s.z>-5);s.fill();advance(s,28);assert.equal(s.state,'full');
s=new Feeding();s.fill();s.start();advance(s,4.7);const snapshot=s.snapshot();s.paused=true;advance(s,30);assert.deepEqual({...s.snapshot(),paused:false},snapshot);s.reset();assert.deepEqual(s.snapshot(),new Feeding().snapshot());
// Export references, face UV bounds, and actual bone transforms must be valid.
const data=JSON.parse(fs.readFileSync(path.join(__dirname,'scene-data.json'),'utf8'));
for(const asset of [data.marisa,data.bowl]){
 const names=new Set(asset.rig.bones.map(b=>b.name));assert.equal(asset.groups.length,asset.elements.length);
 for(const b of asset.rig.bones)assert(!b.parent||names.has(b.parent));
 for(const e of asset.elements){for(let i=0;i<3;i++)assert(e.to[i]>e.from[i]);for(const f of Object.values(e.faces))for(const uv of f.uv)assert(uv>=0&&uv<=256);}
}
// One representative vertex per cube, repeated to exercise the production poser.
const v=data.marisa.elements.flatMap(e=>Array.from({length:36},()=>[...e.from,0,0,0,1,0]).flat());
for(const clip of Object.keys(data.marisa.rig.clips))for(const t of [0,.4,.55,.8,1.2,1.65,2.2,2.7])assert(poseVertices(v,data.marisa.rig,t,true,false,clip).every(Number.isFinite));
const initial=poseVertices(v,data.marisa.rig,0,true,false,'idle'),recovered=poseVertices(v,data.marisa.rig,2.7,true,false,'bite');assert(initial.every((x,i)=>Math.abs(x-recovered[i])<1e-6));
console.log('PASS: food accounting, empty/refill, pause/reset, 24/60/144 Hz, 8 swallows, export references, finite poses and exact recovery.');
