const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {actions,sample,Player}=require('./interactions.js');
let samples=0;
for(const [id,a] of Object.entries(actions)){
 const p=new Player();p.select(id);p.update(1000);
 assert.equal(p.time,a.duration);assert.equal(p.playing,false);
 const end=p.snapshot();
 assert.equal(end.hand,0);assert.equal(end.heart,0);
 for(const actor of end.actors){for(const key of ['y','z','yaw','lean','tilt','tongue','hat'])assert.equal(actor[key],0,`${id} reset ${key}`);assert.equal(actor.face,'rest');}
 p.select(id);p.seek(2);const paused=p.time;p.update(3);assert.equal(p.time,paused);
 p.select(id);p.speed=.5;p.update(2);assert.equal(p.time,1);
 p.select(id);p.speed=1;p.loop=true;p.update(a.duration+.25);assert.equal(p.time,.25);assert.equal(p.playing,true);
 p.select(id);p.loop=false;p.update(.9);p.select('rub');assert.equal(p.time,0);
 const one=new Player(),many=new Player();one.select(id);many.select(id);one.update(2.4);for(let i=0;i<144;i++)many.update(1/60);assert.ok(Math.abs(one.time-many.time)<1e-10);
 for(let t=0;t<=a.duration;t+=1/60){const s=sample(id,t);for(const actor of s.actors){for(const [k,v] of Object.entries(actor))if(typeof v==='number')assert.ok(Number.isFinite(v),`${id} ${k}`);assert.ok(actor.y>=0);assert.ok(actor.tongue>=0&&actor.tongue<=1);}samples++;}
}
assert.ok(sample('lick',3.61).actors[0].tongue>.99);
assert.ok(sample('lick',6.31).actors[1].tongue>.99);
assert.throws(()=>sample('missing',0));
const report={passed:true,actions:Object.keys(actions).length,samples,checks:['end recovery','interrupt and restart','pause','loop','speed','frame-rate independence','finite poses','no negative character height','both licking turns']};
fs.writeFileSync(path.join(__dirname,'verification.json'),JSON.stringify(report,null,2));console.log(report);
