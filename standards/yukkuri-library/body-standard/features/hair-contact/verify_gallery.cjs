/* Open the delivered local gallery in Chrome and exercise every listed model. */
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const body=path.resolve(__dirname,'../..'),lib=path.dirname(body),root=path.resolve(lib,'../..');
const out=path.join(body,'previews/current/all-models'),reports=path.join(body,'reports/current');
const {chromium}=require(path.join(root,'tools/browser-check/node_modules/playwright-core'));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const checks=[],errors=[];
 try{
  const page=await browser.newPage({viewport:{width:1250,height:1060}});
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto('file:///'+path.join(out,'index.html').replaceAll('\\','/'));
  await page.waitForFunction(()=>window.HAIR_GALLERY?.ready,{},{timeout:120000});
  const ids=await page.evaluate(()=>HAIR_GALLERY.ids);
  for(const id of ids){
   await page.selectOption('#character',id);
   const result=await page.evaluate(()=>{
    const api=HAIR_GALLERY,action=api.state.action,c=api.model(api.state.id).clips[action],frames=[];
    for(const phase of [0,.25,.75,1]){
     api.set({playing:false,action,time:c.duration*phase,az:Math.PI});
     const canvas=document.getElementById('view'),data=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;
     let visible=0,signature=0;for(let i=0;i<data.length;i+=4){if(data[i+3])visible++;signature=(signature+data[i]*(i+1)+data[i+1]*3+data[i+2]*7)>>>0;}
     frames.push({phase,visible,signature});
    }return {action,frames};
   });
   checks.push({id,entry:'all-models',passed:result.frames.every(f=>f.visible>3000)&&new Set(result.frames.map(f=>f.signature)).size>1,...result});
   for(const suffix of ['.png','-views.png','-walk.gif'])if(!fs.existsSync(path.join(out,id+suffix)))throw new Error('Missing gallery image '+id+suffix);
  }
  await page.evaluate(()=>HAIR_GALLERY.set({id:'reimu_deibu',playing:false,time:.3,az:1.05}));
  await page.screenshot({path:path.join(out,'browser-preview.png'),fullPage:true});
  await page.click('[data-angle="3.141593"]');
  checks.push({name:'back_button',passed:Math.abs(await page.evaluate(()=>HAIR_GALLERY.state.az)-Math.PI)<1e-5});
  await page.click('#reset');checks.push({name:'reset_button',passed:await page.evaluate(()=>HAIR_GALLERY.state.az===.55&&HAIR_GALLERY.state.time===0)});
  // The affected standalone pages use a different skinning entry point.
  const cfg=JSON.parse(fs.readFileSync(path.join(body,'recipe.json')));
  for(const id of ['reimu','patchouli','reimu_deibu']){
   await page.goto('file:///'+path.join(lib,cfg.sources[id].export,'index.html').replaceAll('\\','/'));
   await page.waitForFunction(()=>window.actionsDebug?.ready(),{},{timeout:120000});
   const visible=await page.evaluate(()=>{actionsDebug.setTime(.3);const c=document.querySelector('canvas'),data=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let pixels=0;for(let i=3;i<data.length;i+=4)if(data[i])pixels++;return pixels;});
   checks.push({id,entry:'standalone',passed:visible>3000,visible});
  }
 }finally{await browser.close();}
 const report={createdAt:new Date().toISOString(),status:errors.length||checks.some(c=>!c.passed)?'failed':'passed',
  scope:'Chrome local-file gallery model switching, sampled rear animation frames, view/reset controls and three standalone entry points',
  hashes:{data:hash(path.join(out,'models.js')),viewer:hash(path.join(out,'viewer.js')),manifest:hash(path.join(out,'manifest.json'))},checks,errors};
 fs.writeFileSync(path.join(reports,'hair-gallery-browser.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));if(report.status==='failed')process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
