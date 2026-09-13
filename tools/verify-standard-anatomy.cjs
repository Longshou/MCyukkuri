const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {pathToFileURL}=require('node:url');
const {chromium}=require('./browser-check/node_modules/playwright-core');
const {AnatomyController}=require('../standards/yukkuri-library/runtime/anatomy.js');
const config=require('../standards/yukkuri-library/behaviors/anatomy.json');
const state=new AnatomyController(config);
assert.equal(state.state().phase,'retracted');assert.equal(state.setContact,undefined);
state.extend();state.update(.12);assert.equal(state.amount,.5);
state.retract();state.update(.1);assert(state.amount>0&&state.amount<.5);
state.extend();state.update(1);assert.equal(state.amount,1);
state.selectPart('mamu');state.update(.3);assert.equal(state.activePart,'peni');
state.update(.35);assert.equal(state.activePart,'mamu');assert(state.amount<1e-8);
state.update(.24);assert.equal(state.amount,1);
for(let i=0;i<500;i++){
 if(i%37===0)state.selectPart(state.selectedPart==='mamu'?'peni':'mamu');
 if(i%29===0)state.targetExtended?state.retract():state.extend();
 state.update(1/60);
 assert(Object.values(state.overrides()).filter(x=>x.position.some(v=>v!==0)).length<=1);
}
assert.throws(()=>state.selectPart('both'),RangeError);
const root=path.resolve('standards/yukkuri-library');
const approved=JSON.parse(fs.readFileSync('standards/yukkuri-v1/components/marisa/contact-anatomy.json','utf8'));
assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root,'components/common/anatomy.json'),'utf8')).elements,approved.elements);
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const errors=[],report={};
 try{
  const p=await browser.newPage({viewport:{width:1200,height:1100}});p.on('pageerror',e=>errors.push(e.message));
  for(const name of ['marisa','marisa_tsumuri','reimu']){
   const out=path.join(root,'assembled',name);
   await p.goto(pathToFileURL(path.join(out,'index.html')).href);await p.waitForFunction(()=>window.actionsDebug?.ready());
   await p.evaluate(()=>actionsDebug.setTime(0));
   assert.equal(await p.evaluate(()=>standardAnatomy.state().amount),0);
   assert.equal(await p.evaluate(()=>typeof standardAnatomy.setContact),'undefined');
   assert.equal(await p.locator('[id*="contact"]').count(),0);
   report[name]=await p.evaluate(()=>{
    const checks=[],first=model.rig.groups.findIndex(x=>x.startsWith('anatomy_'));
    for(const part of DATA.anatomy.parts)for(const action of Object.keys(DATA.motions)){
     const t=DATA.motions[action].duration*.42,pose=()=>poseVertices(model.vertices,model.rig,t,true,false,action);
     const original=pose();
     const draw=(v,az)=>renderModel(v,textures.neutral,240,240,az,.04,17,{centerY:9.5,textureSize:DATA.textureSize});
     const az=part.id==='mamu'?Math.PI:0,hidden=draw(original,az);
     const absent=draw(original.filter((_,i)=>!model.rig.groups[Math.floor(i/288)].startsWith('anatomy_')),az);
     if(hidden.some((x,i)=>x!==absent[i]))throw Error(action+' resting part visible');
     const controller=new AnatomyController(DATA.anatomy);controller.extend(part.id);controller.update(2);
     model.rig.boneOverrides=controller.overrides();const visible=pose();delete model.rig.boneOverrides;
     if(!visible.every(Number.isFinite))throw Error('Nonfinite pose');
     if(original.slice(0,first*288).some((x,i)=>x!==visible[i]))throw Error(action+' altered body');
     for(let cube=first;cube<model.rig.groups.length;cube++)if(model.rig.groups[cube]!==part.bone)
      if(original.slice(cube*288,(cube+1)*288).some((x,i)=>x!==visible[cube*288+i]))throw Error('Inactive part moved');
     if(part.id==='peni')for(const amount of [.25,.5,.75,1]){
      controller.amount=amount;model.rig.boneOverrides=controller.overrides();const sample=pose();delete model.rig.boneOverrides;
      const cube=model.rig.groups.indexOf(part.bone);
      for(let k=cube*288+48;k<cube*288+96;k+=8)
       if(!Array.from({length:model.bodyCubeCount},(_,i)=>i).some(i=>insideCube(sample.slice(k,k+3),sample,i)))throw Error(action+' root separates at '+amount);
     }
     let differences=draw(visible,az).reduce((n,x,i)=>n+(x!==hidden[i]),0);
     if(!differences)for(const side of [Math.PI/2,Math.PI,Math.PI*1.5]){const a=draw(original,side),b=draw(visible,side);differences+=b.reduce((n,x,i)=>n+(x!==a[i]),0);}
     if(!differences&&action!=='shell_retract')throw Error(action+' extension invisible');
     checks.push({part:part.id,action,changedChannels:differences});
    }
    return checks;
    function insideCube(point,vertices,cube){
     const at=i=>vertices.slice(cube*288+i,cube*288+i+3),sub=(a,b)=>a.map((v,i)=>v-b[i]);
     const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],dot=(a,b)=>a.reduce((n,v,i)=>n+v*b[i],0);
     const origin=at(8),u=sub(at(0),origin),v=sub(at(16),origin),w=sub(at(48),origin),d=sub(point,origin),det=dot(u,cross(v,w));
     if(Math.abs(det)<1e-10)return false;
     return [dot(d,cross(v,w))/det,dot(d,cross(w,u))/det,dot(d,cross(u,v))/det].every(q=>q>=-1e-5&&q<=1+1e-5);
    }
   });
   await p.locator('#anatomy-front').click();await p.locator('canvas').screenshot({path:path.join(out,'anatomy-hidden.png')});
   await p.locator('#anatomy-extend').click();await p.waitForFunction(()=>standardAnatomy.state().amount===1);
   assert.equal(await p.evaluate(()=>actionsDebug.state().playing),false);
   await p.locator('[data-angle="1.5708"]').click();await p.locator('canvas').screenshot({path:path.join(out,'anatomy-peni-side.png')});
   await p.locator('#anatomy-part').selectOption('mamu');await p.waitForFunction(()=>standardAnatomy.state().part==='mamu'&&standardAnatomy.state().amount===1);
   await p.locator('#anatomy-rear').click();await p.locator('canvas').screenshot({path:path.join(out,'anatomy-mamu-rear.png')});
   await p.locator('#anatomy-retract').click();await p.waitForFunction(()=>standardAnatomy.state().amount===0);
   const box=await p.locator('canvas').boundingBox(),az=await p.evaluate(()=>actionsDebug.state().az);
   await p.mouse.move(box.x+300,box.y+200);await p.mouse.down();await p.mouse.move(box.x+450,box.y+240,{steps:5});await p.mouse.up();
   assert.notEqual(await p.evaluate(()=>actionsDebug.state().az),az);assert.equal(await p.evaluate(()=>standardAnatomy.state().amount),0);
  }
  await p.goto(pathToFileURL(path.join(root,'compare.html')).href);
  await p.waitForFunction(()=>document.querySelectorAll('iframe').length===2);
  const frames=p.frames().slice(1);for(const f of frames)await f.waitForFunction(()=>window.actionsDebug?.ready());
  await p.locator('#anatomy-extend').click();for(const f of frames)await f.waitForFunction(()=>standardAnatomy.state().amount===1);
  await p.locator('#anatomy-part').selectOption('mamu');for(const f of frames)await f.waitForFunction(()=>standardAnatomy.state().part==='mamu'&&standardAnatomy.state().amount===1);
  await p.locator('#anatomy-retract').click();for(const f of frames)await f.waitForFunction(()=>standardAnatomy.state().amount===0);
  assert.deepEqual(errors,[]);report.interaction={buttons:true,paused:true,orbitOnly:true,compareSynchronized:true,exclusive:true,pageErrors:errors};
  fs.writeFileSync(path.join(root,'anatomy-verification.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({characters:['marisa','marisa_tsumuri','reimu'],motionPartSamples:Object.values(report).filter(Array.isArray).reduce((n,a)=>n+a.length,0),...report.interaction},null,2));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
