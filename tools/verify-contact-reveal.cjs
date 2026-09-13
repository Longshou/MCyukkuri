const assert=require('node:assert/strict');
const path=require('node:path');
const fs=require('node:fs');
const {pathToFileURL}=require('node:url');
const {chromium}=require('./browser-check/node_modules/playwright-core');
const {ContactReveal}=require('../standards/yukkuri-v1/runtime/contact-reveal.js');
const config=require('../standards/yukkuri-v1/behaviors/contact-reveal.json');
const state=new ContactReveal(config);
state.setContact(true);state.update(.12);assert.equal(state.amount,.5);
state.setContact(false);state.update(.1);assert(state.amount<.5&&state.amount>0);
state.setContact(true);state.update(1);assert.equal(state.amount,1);
state.setContact(false);state.update(1);assert.equal(state.amount,0);
state.setContact(true);state.update(1);state.selectPart('mamu');
state.update(.3);assert.equal(state.activePart,'peni');assert(state.amount>0&&state.amount<1);
state.update(.4);assert.equal(state.activePart,'mamu');assert.equal(state.amount,0);
state.update(.24);assert.equal(state.amount,1);
for(let i=0;i<300;i++){
 if(i%37===0)state.selectPart(state.selectedPart==='mamu'?'peni':'mamu');
 if(i%29===0)state.setContact(!state.touching);
 state.update(1/60);
 assert(Object.values(state.overrides()).filter(x=>x.position.some(v=>v!==0)).length<=1,'Two parts exposed');
}
assert.throws(()=>state.selectPart('both'),RangeError);
const exported=JSON.parse(fs.readFileSync('standards/yukkuri-v1/assembled/marisa/marisa.animation.json','utf8')).animations;
assert(!exported['animation.marisa.contact_reveal'],'Obsolete simultaneous reveal clip');
for(const part of config.parts){
 const clip=exported['animation.marisa.contact_'+part.id];assert(clip);
 for(const other of config.parts)assert.deepEqual(clip.bones[other.bone].position['1'],other.id===part.id?part.offset:[0,0,0]);
 assert.deepEqual(clip.bones.anatomy_peni.rotation['1'],part.id==='peni'?[-25,0,0]:[0,0,0]);
}
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const errors=[],report={};
 try{
  const p=await browser.newPage({viewport:{width:1200,height:1100}});p.on('pageerror',e=>errors.push(e.message));
  const output=path.resolve('standards/yukkuri-v1/assembled/marisa');
  await p.goto(pathToFileURL(path.join(output,'index.html')).href);
  await p.waitForFunction(()=>window.actionsDebug?.ready());
  assert.deepEqual(await p.evaluate(()=>contactDebug.state()),{amount:0,touching:false,part:'peni',selectedPart:'peni'});
  await p.locator('#contact-front').click();
  await p.waitForTimeout(100);
  await p.locator('canvas').screenshot({path:path.join(output,'contact-hidden.png')});
  report.render=await p.evaluate(()=>{
   const checks=[];
   const firstContact=model.rig.groups.findIndex(x=>x.startsWith('anatomy_'));
   for(const part of DATA.contactReveal.parts)for(const action of ['puff','stretch','waddle','flee','exercise_sides','exercise_vertical','press','taiso']){
    const t=action==='stretch'?1.8:1.2;
    const pose=()=>action==='flee'?fleePose('b',t,-Math.PI/2).vertices:poseVertices(model.vertices,model.rig,t,true,false,action);
    const original=pose();
    const short=original.slice(0,firstContact*288);
    const camera=(part.id==='mamu'?Math.PI:0)+(action==='flee'?Math.PI/2:0);
    const draw=v=>renderModel(v,textures.neutral,640,640,camera,.04,10,{centerY:6.5});
    const hidden=draw(original),absent=draw(short);
    assertFinite(original);
    if(hidden.some((x,i)=>x!==absent[i]))throw Error(action+' resting parts visible');
    const reveal=new ContactReveal(DATA.contactReveal);reveal.selectPart(part.id);reveal.update(0);reveal.setContact(true);reveal.update(1);
    model.rig.boneOverrides=reveal.overrides();
    const visible=pose();delete model.rig.boneOverrides;
    assertFinite(visible);
    if(part.id==='peni'&&action==='puff'){
     const tipCube=model.rig.groups.lastIndexOf('anatomy_peni');
     const tipY=visible[tipCube*288+1],restY=original[tipCube*288+1];
     if(tipY-restY<1)throw Error('Peni tip must curve upward');
    }
    if(part.id==='peni')for(const amount of [.25,.5,.75,1]){
     reveal.amount=amount;model.rig.boneOverrides=reveal.overrides();const sample=pose();delete model.rig.boneOverrides;
     const rootCube=model.rig.groups.indexOf('anatomy_peni');
     for(let k=rootCube*288+48;k<rootCube*288+96;k+=8){
      const point=sample.slice(k,k+3);
      if(!Array.from({length:model.spec.bodyCubeCount},(_,i)=>i).some(i=>insideCube(point,sample,i)))throw Error(action+' root separates at '+amount);
     }
    }
    if(original.slice(0,firstContact*288).some((x,i)=>x!==visible[i]))throw Error(action+' altered original body');
    for(let cube=firstContact;cube<model.rig.groups.length;cube++)if(model.rig.groups[cube]!==part.bone){
     if(original.slice(cube*288,(cube+1)*288).some((x,i)=>x!==visible[cube*288+i]))throw Error('Inactive part moved');
    }
    const rendered=draw(visible);const differences=rendered.reduce((n,x,i)=>n+(x!==hidden[i]),0);
    if(differences===0)throw Error(action+' reveal invisible');
    checks.push({part:part.id,action,hiddenMatchesOriginal:true,inactivePartUnchanged:true,changedChannels:differences});
   }
   return checks;
   function assertFinite(vertices){if(!vertices.every(Number.isFinite))throw Error('Nonfinite vertices');}
   function insideCube(point,vertices,cube){
    const at=i=>vertices.slice(cube*288+i,cube*288+i+3),sub=(a,b)=>a.map((v,i)=>v-b[i]);
    const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],dot=(a,b)=>a.reduce((n,v,i)=>n+v*b[i],0);
    const origin=at(8),u=sub(at(0),origin),v=sub(at(16),origin),w=sub(at(48),origin),d=sub(point,origin),det=dot(u,cross(v,w));
    if(Math.abs(det)<1e-10)return false;
    return [dot(d,cross(v,w))/det,dot(d,cross(w,u))/det,dot(d,cross(u,v))/det].every(q=>q>=-1e-5&&q<=1+1e-5);
   }
  });
  const hold=await p.locator('#contact-hold').boundingBox();
  await p.mouse.move(hold.x+hold.width/2,hold.y+hold.height/2);await p.mouse.down();
  await p.waitForFunction(()=>contactDebug.state().amount===1);
  assert.equal(await p.evaluate(()=>actionsDebug.state().playing),false);
  await p.locator('canvas').screenshot({path:path.join(output,'contact-visible.png')});
  await p.mouse.up();await p.waitForFunction(()=>contactDebug.state().amount===0);
  await p.evaluate(()=>contactDebug.setContact(true));await p.waitForFunction(()=>contactDebug.state().amount===1);
  await p.locator('[data-view="1.5708"]').click();
  await p.locator('canvas').screenshot({path:path.join(output,'contact-peni-side.png')});
  await p.locator('#contact-part').selectOption('mamu');
  await p.waitForFunction(()=>contactDebug.state().part==='mamu'&&contactDebug.state().amount===1);
  await p.locator('#contact-rear').click();
  await p.locator('canvas').screenshot({path:path.join(output,'contact-mamu-rear.png')});
  await p.evaluate(()=>contactDebug.setContact(false));await p.waitForFunction(()=>contactDebug.state().amount===0);
  await p.locator('canvas').screenshot({path:path.join(output,'contact-mamu-hidden.png')});
  await p.locator('#contact-part').selectOption('peni');await p.locator('#contact-front').click();
  await p.locator('#contact-mode').check();
  const hit=await p.evaluate(()=>{
   const c=document.getElementById('view'),r=c.getBoundingClientRect(),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;
   for(let y=Math.floor(c.height/2);y<c.height;y++)for(let x=Math.floor(c.width/2);x<c.width;x++)if(d[(y*c.width+x)*4+3])return {x:r.left+(x+.5)*r.width/c.width,y:r.top+(y+.5)*r.height/c.height};
   throw Error('No visible model');
  });
  await p.mouse.move(hit.x,hit.y);await p.mouse.down();await p.waitForFunction(()=>contactDebug.state().amount===1);
  await p.mouse.move(10,10);await p.waitForFunction(()=>contactDebug.state().amount===0);await p.mouse.up();
  await p.evaluate(()=>{contactDebug.setContact(true);window.dispatchEvent(new Event('blur'));});
  assert.equal(await p.evaluate(()=>contactDebug.state().touching),false);
  await p.locator('#contact-mode').uncheck();
  await p.locator('#contact-hold').focus();await p.keyboard.down('Space');await p.waitForFunction(()=>contactDebug.state().amount===1);
  await p.keyboard.up('Space');await p.waitForFunction(()=>contactDebug.state().amount===0);
  await p.goto(pathToFileURL(path.resolve('standards/yukkuri-v1/assembled/reimu/index.html')).href);
  await p.waitForFunction(()=>window.actionsDebug?.ready());assert.equal(await p.locator('#contact-controls').count(),0);
  assert.deepEqual(errors,[]);
  report.interaction={button:true,pausedAnimation:true,canvasContact:true,leaveRestores:true,blurReleases:true,keyboard:true,mutuallyExclusiveSwitch:true,reimuUnchanged:true,errors};
  fs.writeFileSync('standards/yukkuri-v1/contact-verification.json',JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report,null,2));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
