/* Exercise the actual generated viewer, exported tracks, contacts and controls. */
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const project=path.resolve(__dirname,'../../../..');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(project,'tools/browser-check/node_modules/playwright-core'));
const read=p=>JSON.parse(fs.readFileSync(path.join(__dirname,p),'utf8'));
const rig=read('assembled/animation-rig.json'),recipes=read('recipes.json');
const exported=read('assembled/marisa-braids.animation.json').animations;
const bb=read('assembled/marisa-braids.bbmodel');
const report={actions:Object.keys(recipes.actions),errors:[],source:'ordinary marisa from current unified library'};
assert.equal(Object.keys(rig.clips).length,7);assert.equal(bb.animations.length,7);
const sample=(keys,t)=>{const entries=Object.entries(keys).map(([t,v])=>[+t,v]).sort((a,b)=>a[0]-b[0]);if(t<=entries[0][0])return entries[0][1];for(let i=1;i<entries.length;i++){const [b,y]=entries[i],[a,x]=entries[i-1];if(t<=b)return x.map((v,j)=>v+(y[j]-v)*(t-a)/(b-a))}return entries.at(-1)[1]};
const near=(a,b,msg,tol=1e-5)=>assert.ok(a.every((v,i)=>Math.abs(v-b[i])<tol),msg+': '+a+' vs '+b);
const vadd=(a,b)=>a.map((v,i)=>v+b[i]);
const grip=(c,side,t)=>vadd([side==='left'?-6.5:6.5,.954,-2.4],sample(c.tracks['grip_'+side].position,t));
for(const [id,c] of Object.entries(rig.clips)){
  assert.equal(c.loop,false);assert.equal(exported['animation.marisa.'+id].animation_length,c.duration);
  for(const [name,channels] of Object.entries(c.tracks))for(const [ch,keys] of Object.entries(channels)){
    for(const [t,v] of Object.entries(keys)){assert.ok(+t>=0&&+t<=c.duration);assert.ok(v.every(Number.isFinite));if(ch==='scale')assert.ok(v.every(n=>n>0))}
    const engine=exported['animation.marisa.'+id].bones[name]?.[ch];
    if(engine)for(const [t,v] of Object.entries(keys))near(engine[t],ch==='rotation'?[-v[0],-v[1],v[2]]:ch==='position'?[-v[0],v[1],v[2]]:v,'export axis conversion');
  }
  for(const side of ['left','right'])near(grip(c,side,0),grip(c,side,c.duration),'braid recovery');
  if(id!=='lift_child'){
    near(sample(c.tracks.hat.position,0),sample(c.tracks.hat.position,c.duration),'hat position recovery');
    near(sample(c.tracks.hat.rotation,0),sample(c.tracks.hat.rotation,c.duration),'hat rotation recovery');
  }
}
// Sample between authored frames as well: grip and carried object must share the
// same interpolation, without accumulating state or relying on playback order.
for(const [id,prop,start,end] of [['pick_stone','stone',1.6,5.5],['swing_sword','sword',1.6,5.8],['pull_grass','grass',1.8,5.6]]){
  const c=rig.clips[id];for(let t=start;t<=end;t+=.037)near(grip(c,'right',t),sample(c.tracks[prop].position,t),'carried '+prop+' stays at grip');
}
const c=rig.clips.lift_child;
for(let t=2;t<=6.2;t+=.037)for(const side of ['left','right'])near(grip(c,side,t),vadd(sample(c.tracks.child.position,t),[side==='left'?-1.7:1.7,.5,0]),'two-sided child support');
near(sample(c.tracks.child.position,6.2),sample(c.tracks.child.position,c.duration),'child remains on hat');
let maxHatGripError=0;
for(const id of ['remove_hat','care_hat']){
 const end=recipes.actions[id].release_time;
 const clip=rig.clips[id];
 for(let t=1.7;t<=end;t+=.037){
  const angles=sample(clip.tracks.hat.rotation,t),pos=sample(clip.tracks.hat.position,t);
  // Both rim contact points lie on the X axis through the hat pivot; rotation
  // must leave their local position fixed while the hat translates.
  for(const side of id==='care_hat'?['left']:['left','right']){
   if(id==='care_hat'){
    const point=grip(clip,side,t).map((v,k)=>v-pos[k]-[0,10,-3][k]);
    const yaw=angles[1]*Math.PI/180;
    const local=[Math.cos(yaw)*point[0]-Math.sin(yaw)*point[2],point[1]+10,Math.sin(yaw)*point[0]+Math.cos(yaw)*point[2]-3];
    assert.ok(bb.elements.some(e=>e.name.startsWith('brim_')&&local.every((v,k)=>v>=e.from[k]-.02&&v<=e.to[k]+.02)),'support stays on turned rim');
    continue;
   }
   const expected=vadd([side==='left'?-8.35:8.35,10,-3],pos),actual=grip(clip,side,t);
   const error=Math.hypot(...actual.map((v,i)=>v-expected[i]));maxHatGripError=Math.max(error,maxHatGripError);
  }
 }
}
assert.ok(maxHatGripError<.001,'supporting braids track detached hat');
report.maxHatGripError=maxHatGripError;
let maxBraidFrameRotation=0,maxBowDeviation=0,maxHatDegreesPerSecond=0;
for(const [id,clip] of Object.entries(rig.clips)){
 for(const [name,chs] of Object.entries(clip.tracks)){
  const keys=Object.entries(chs.rotation).map(([t,v])=>[+t,v]).sort((a,b)=>a[0]-b[0]);
  for(let i=1;i<keys.length;i++){
   const delta=Math.max(...keys[i][1].map((v,k)=>Math.abs(v-keys[i-1][1][k])));
   if(name.startsWith('braid_'))maxBraidFrameRotation=Math.max(maxBraidFrameRotation,delta);
   if(name==='hat')maxHatDegreesPerSecond=Math.max(maxHatDegreesPerSecond,delta/(keys[i][0]-keys[i-1][0]));
  }
 }
 for(const side of ['left','right']){
  const bindRoot=[side==='left'?-6.5:6.5,8.48,-2.4];
  for(const t of Object.keys(clip.tracks['grip_'+side].position).map(Number)){
   const root=vadd(bindRoot,sample(clip.tracks[`braid_${side}_0`].position,t));
   const end=grip(clip,side,t),d=end.map((v,k)=>v-root[k]),l2=d.reduce((s,v)=>s+v*v,0);
   for(let i=1;i<6;i++){
    const p=vadd([bindRoot[0],8.48-i*1.06,-2.4],sample(clip.tracks[`braid_${side}_${i}`].position,t));
    const offset=p.map((v,k)=>v-root[k]),u=offset.reduce((s,v,k)=>s+v*d[k],0)/l2;
    maxBowDeviation=Math.max(maxBowDeviation,Math.hypot(...offset.map((v,k)=>v-u*d[k])));
   }
  }
 }
}
assert.ok(maxBowDeviation<.17,'braids stay nearly straight');
assert.ok(maxBraidFrameRotation<30,'no rapid braid spin between exported frames');
assert.ok(maxHatDegreesPerSecond<=51,'hat turns slowly');
report.straightBraidRevision={maxBowDeviation,maxBraidFrameRotation,maxHatDegreesPerSecond};
// Reusable strand extension is bounded and unrelated rear hair cannot inherit it.
for(const clip of Object.values(rig.clips))for(const [name,chs] of Object.entries(clip.tracks))if(name.startsWith('braid_'))for(const v of Object.values(chs.scale))assert.ok(v[1]<=1.4,'braid reach bound');
report.exportParity=true;report.gripInterpolation=true;report.twoBraidSupport=true;report.recovery=true;
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{
  const p=await browser.newPage({viewport:{width:1280,height:1000}});p.on('pageerror',e=>report.errors.push(e.message));
  await p.goto(pathToFileURL(path.join(__dirname,'index.html')).href);await p.waitForFunction(()=>window.braidDebug?.ready());
  for(const [id,a] of Object.entries(recipes.actions)){
    await p.locator(`[data-action="${id}"]`).click();
    for(const t of [0,...a.phases.map(x=>x[0]),a.duration]){
      const ok=await p.evaluate(t=>{braidDebug.setTime(t);braidDebug.draw();return braidDebug.pose().every(Number.isFinite)},t);assert.ok(ok,'finite vertices '+id+' '+t);
    }
    await p.evaluate(t=>braidDebug.setTime(t),a.duration-.02);await p.locator('#play').click();
    await p.waitForFunction(()=>!braidDebug.state().playing);near([await p.evaluate(()=>braidDebug.state().time)],[a.duration],'nonloop endpoint',.001);
  }
  const support=await p.evaluate(()=>{
    braidDebug.select('lift_child');braidDebug.setTime(8.4);const model=braidDebug.model(),v=braidDebug.pose();
    const box=i=>{const lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];for(let j=i*288;j<(i+1)*288;j+=8)for(let k=0;k<3;k++){lo[k]=Math.min(lo[k],v[j+k]);hi[k]=Math.max(hi[k],v[j+k])}return {lo,hi}};
    const shelf=box(model.rig.groups.indexOf('hat_front')),child=model.rig.groups.map((g,i)=>g==='child'?box(i):null).filter(Boolean);
    const bottom=Math.min(...child.map(b=>b.lo[1])),feet=child.filter(b=>Math.abs(b.lo[1]-bottom)<.01);
    return {gap:bottom-shelf.hi[1],shelf,feet,fullySupported:feet.every(b=>b.lo[0]>=shelf.lo[0]-.001&&b.hi[0]<=shelf.hi[0]+.001&&b.lo[2]>=shelf.lo[2]-.001&&b.hi[2]<=shelf.hi[2]+.001)};
  });
  assert.ok(Math.abs(support.gap)<.001,'child contacts shelf');assert.ok(support.fullySupported,'whole bottom footprint fits shelf');report.hatShelf=support;
  report.hatCare=await p.evaluate(()=>{
   braidDebug.select('care_hat');const m=braidDebug.model(),groups=m.rig.groups;
   const frames=[];
   for(const t of [11.8,12.8,14,15.2]){
    braidDebug.setTime(t);const v=braidDebug.pose(),lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];
    for(let i=0;i<groups.length;i++)if(['hat','hat_front','bow'].includes(groups[i]))for(let j=i*288;j<(i+1)*288;j+=8)for(let k=0;k<3;k++){lo[k]=Math.min(lo[k],v[j+k]);hi[k]=Math.max(hi[k],v[j+k])}
    frames.push({time:t,lo,hi});
   }
   return {frames};
  });
  for(const frame of report.hatCare.frames){
   assert.ok(Math.abs(frame.lo[1])<.001,'hat rests at ground level during care');
   assert.ok(frame.hi[2]<-5.8,'lowered hat clears face');
   near(sample(rig.clips.care_hat.tracks.hat.rotation,frame.time),[0,180,0],'hat faces worker while wiping');
  }
  report.hatCare.turnedTowardWorker=true;
  await p.evaluate(()=>{braidDebug.select('pick_stone');braidDebug.setTime(3)});
  const before=await p.evaluate(()=>braidDebug.state());const canvas=p.locator('canvas');const bounds=await canvas.boundingBox();
  await p.mouse.move(bounds.x+300,bounds.y+220);await p.mouse.down();await p.mouse.move(bounds.x+410,bounds.y+245);await p.mouse.up();await p.mouse.wheel(0,100);
  const after=await p.evaluate(()=>braidDebug.state());assert.notEqual(before.az,after.az);assert.notEqual(before.span,after.span);assert.equal(after.time,3);report.pausedCamera=true;
  await p.locator('#time').fill('1.23');await p.locator('#time').dispatchEvent('input');near([await p.evaluate(()=>braidDebug.state().time)],[1.23],'scrubbing');
  for(const [id,t,view] of [['pick_stone',3,'three'],['swing_sword',2.6,'front'],['pull_grass',3.5,'front'],['remove_hat',4.5,'side'],['care_hat',12.8,'side'],['wave_goodbye',2.6,'front'],['lift_child',8.4,'three']]){
    await p.evaluate(([id,t])=>{braidDebug.select(id);braidDebug.setTime(t)},[id,t]);await p.locator(`[data-view="${view}"]`).click();await p.evaluate(()=>braidDebug.draw());
    fs.mkdirSync(path.join(__dirname,'reports/current'),{recursive:true});await canvas.screenshot({path:path.join(__dirname,'reports/current',id+'.png')});
  }
  await p.setViewportSize({width:430,height:1000});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);report.mobileLayout=true;
  assert.deepEqual(report.errors,[]);
  fs.writeFileSync(path.join(__dirname,'reports/current/verification.json'),JSON.stringify(report,null,2));
  console.log('PASS: seven actions; export parity; grip interpolation; child support; recovery; playback, camera, scrubbing and mobile layout.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
