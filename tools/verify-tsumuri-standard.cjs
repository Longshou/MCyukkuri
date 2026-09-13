const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const {chromium}=require('./browser-check/node_modules/playwright-core');
const root=path.resolve('standards/yukkuri-library'),out=path.join(root,'assembled/marisa_tsumuri');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const source=read(path.join(root,'components/marisa-tsumuri/shell.json')).elements;
const bb=read(path.join(out,'marisa_tsumuri.bbmodel'));
const geo=read(path.join(out,'marisa_tsumuri.geo.json'))['minecraft:geometry'][0];
const exported=geo.bones.find(b=>b.name==='shell').cubes;
assert.equal(source.length,exported.length);
for(let i=0;i<source.length;i++){
 const e=source[i],b=bb.elements.find(b=>b.name===e.name),g=exported[i];
 for(const k of ['from','to','origin','rotation','faces'])assert.deepEqual(b[k],e[k]);
 assert.deepEqual(g.origin,e.from);assert.deepEqual(g.rotation,e.rotation);assert.deepEqual(g.pivot,e.origin);
}
assert.equal(geo.description.texture_width,256);
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const errors=[];
 try{
  const p=await browser.newPage({viewport:{width:1200,height:1100}});p.on('pageerror',e=>errors.push(e.message));
  await p.goto(pathToFileURL(path.join(out,'index.html')).href);await p.waitForFunction(()=>window.actionsDebug?.ready());
  await p.evaluate(()=>actionsDebug.setTime(0));
  for(const [view,angle] of [['front','0'],['side','1.5708'],['back','3.14159']]){
   await p.locator(`[data-angle="${angle}"]`).click();await p.locator('canvas').screenshot({path:path.join(out,`standard-${view}.png`)});
  }
  const checks=await p.evaluate(()=>{
   const m=actionsDebug.model(),pose=t=>poseVertices(m.vertices,m.rig,t,true,false,'shell_retract');
   const first=pose(0),last=pose(6);if(first.some((x,i)=>Math.abs(x-last[i])>1e-7))throw Error('Shell retreat fails to restore the body');
   const all=pose(2.5),body=[],shell=[],c=Math.cos(25*Math.PI/180),s=Math.sin(25*Math.PI/180);
   for(let cube=0;cube<m.rig.groups.length;cube++){
    const group=m.rig.groups[cube];if(group.startsWith('puff_'))continue;
    const lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];
    for(let i=cube*288;i<(cube+1)*288;i+=8){
     const y=all[i+1]-5.9,z=all[i+2]-1.75,v=[all[i],c*y+s*z,-s*y+c*z];
     for(let k=0;k<3;k++){lo[k]=Math.min(lo[k],v[k]);hi[k]=Math.max(hi[k],v[k]);}
    }
    (group==='shell'?shell:body).push({lo,hi,group});
   }
   const collisions=[];for(const a of body)for(const b of shell)if(a.lo.every((v,k)=>Math.min(a.hi[k],b.hi[k])-Math.max(v,b.lo[k])>.025))collisions.push(a.group);
   if(collisions.length)throw Error('Body penetrates shell: '+[...new Set(collisions)]);
   return {recovery:true,withdrawnShellIntersections:collisions.length};
  });
  await p.locator('#motion').selectOption('shell_retract');await p.evaluate(()=>actionsDebug.setTime(2.5));
  await p.locator('canvas').screenshot({path:path.join(out,'standard-withdrawn.png')});
  await p.locator('#motion').selectOption('still');await p.evaluate(()=>actionsDebug.setTime(0));
  await p.setViewportSize({width:430,height:1000});
  assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(out,'standard-verification.json'),JSON.stringify({sourceGeometryPreserved:true,rotatedExports:true,textureSize:256,...checks,mobileLayout:true,pageErrors:errors},null,2));
  console.log('Standard shell exports, withdrawal, recovery, and mobile preview passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
