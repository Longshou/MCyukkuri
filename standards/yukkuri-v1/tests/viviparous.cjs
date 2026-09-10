const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
require('../runtime/growth.js');require('../runtime/stem-birth.js');const birth=require('../runtime/viviparous-birth.js');
const read=ref=>JSON.parse(fs.readFileSync(path.join(__dirname,'..',ref),'utf8'));
const data={growth:read('profiles/growth.json'),viviparous:read('behaviors/viviparous-birth.json'),body_height:8.48};
for(const count of [1,2,3,4,5])for(const motherScale of [.8,1,1.2]){
 let previous=0;
 for(let time=0;time<=32;time+=.07){const s=birth.sample(data,time,motherScale,count);assert.equal(s.children.length,count);assert(s.born>=previous);previous=s.born;assert(s.belly>=0&&s.belly<=1);assert(s.outlet[2]<0);for(const c of s.children){assert(c.position.every(Number.isFinite));assert(c.position[1]>=0);assert.equal(c.stage,'baby');assert(c.scale>=.12&&c.scale<.3);if(time<14)assert(!c.visible);}}
 for(let i=0;i<count;i++){const t=birth.birthTime(data,i),a=birth.sample(data,t-1e-8,motherScale,count).children[i],b=birth.sample(data,t,motherScale,count).children[i];assert(Math.hypot(...a.position.map((v,k)=>v-b.position[k]))<1e-6,'continuous exit');assert(!a.born&&b.born);const ground=birth.sample(data,t+1.1+1e-6,motherScale,count).children[i];assert(ground.landed);assert.equal(ground.position[1],0);}
 const end=birth.sample(data,32,motherScale,count);assert.equal(end.belly,0);assert.equal(end.opening,0);assert(end.children.every(c=>c.landed));assert.equal(end.contraction,0);
 const run=birth.create(data,{scale:motherScale},{count});assert.equal(run.advance(16).events.length,1);const saved=JSON.parse(JSON.stringify(run.save()));const restored=birth.create(data,saved.mother,{count});restored.restore(saved);assert.equal(restored.advance(0).events.length,0);assert.equal(restored.advance(100).events.length,count-1);assert.equal(restored.advance(1).events.length,0);assert.throws(()=>restored.restore({...saved,emitted:[]}));
 const fast=birth.create(data,{scale:motherScale},{count});assert.equal(fast.advance(100).events.length,count);assert.equal(fast.advance(100).events.length,0);
}
let draws=0;const random=birth.create(data,{}, {random:()=>{draws++;return .99;}});random.advance(32);assert.equal(draws,1);assert.equal(random.state().count,5);
for(const count of [0,6,NaN,1.2])assert.throws(()=>birth.sample(data,0,1,count));assert.throws(()=>birth.create(data,{stage:'baby'}));assert.throws(()=>birth.create(data,{reproductionMode:'stem'}));assert.throws(()=>birth.sample(data,NaN));
const a=birth.create(data,{id:'mother-A'},{count:1}).advance(32).events[0],b=birth.create(data,{id:'mother-B'},{count:1}).advance(32).events[0];assert.notEqual(a.childId,b.childId);
console.log('Viviparous checks passed: all counts/adult scales, hidden fetuses, front outlet, continuous emergence, baby stage, floor landing, recovery, once-only births and reload.');
