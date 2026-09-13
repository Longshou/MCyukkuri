/* Exercise the actual file-based gallery and body effect controls. */
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const {pathToFileURL}=require('url');
const root=path.resolve(__dirname,'../../../..');
const {chromium}=require(path.join(root,'tools/browser-check/node_modules/playwright-core'));
const quick=process.argv.includes('--quick');
const out=path.join(__dirname,'previews/current/body-effects');fs.mkdirSync(out,{recursive:true});
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const report={startedAt:new Date().toISOString(),quick,previewDataSha256:hash(path.join(__dirname,'previews/current/data.js')),viewerSha256:hash(path.join(__dirname,'previews/current/viewer.js')),exportsSha256:hash(path.join(__dirname,'reports/current/exports.json')),checks:[],errors:[]};
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1050},deviceScaleFactor:1});page.on('pageerror',e=>report.errors.push(e.message));
  await page.goto(pathToFileURL(path.join(__dirname,'previews/current/index.html')).href,{timeout:120000});
  await page.waitForFunction(()=>window.propsPreview?.ready(),{},{timeout:120000});
  await page.locator('[data-filter="body"]').click();
  const ids=await page.evaluate(()=>propsPreview.assets().filter(a=>a.category==='body'&&a.id!=='body_wounds').map(a=>a.id));
  if(ids.length!==8||await page.locator('.card').count()!==9)throw Error('Expected eight effect examples and the wound placement editor');
  await page.screenshot({path:path.join(__dirname,'previews/current/body-sheet.png'),fullPage:true});
  for(const id of ids){
   await page.evaluate(id=>propsPreview.open(id),id);
   for(const species of (quick?[0]:[0,1])){
    await page.evaluate(n=>propsPreview.setParameter('species',n),species);
    const name=species===0?'reimu':'marisa';
    for(const angle of (quick?['oblique']:['front','oblique','side','back'])){
     await page.locator(`[data-view="${angle}"]`).click();await page.locator('#view').screenshot({path:path.join(out,`${id}-${name}-default-${angle}.png`)});
    }
    if(!quick&&id!=='body_leak'){
     await page.locator('[data-view="oblique"]').click();
     const states=[];
     for(const grade of [0,1,2,3]){
      await page.evaluate(n=>propsPreview.setParameter('severity',n),grade);
      states.push(await page.evaluate(()=>JSON.stringify(propsPreview.geometry(propsPreview.state().selected,propsPreview.state().parameters))));
      await page.locator('#view').screenshot({path:path.join(out,`${id}-${name}-grade-${grade}.png`)});
     }
     if(new Set(states).size!==4)throw Error('Indistinguishable grade geometry/UV states '+id);
     await page.evaluate(()=>propsPreview.setParameter('severity',2));
    }
    if(!quick){
     for(const scale of [.3,1.2]){
      await page.evaluate(n=>propsPreview.setParameter('ownerScale',n),scale);await page.locator('#view').screenshot({path:path.join(out,`${id}-${name}-scale-${scale}.png`)});
     }
     const scaled=await page.evaluate(({id,species})=>{const a=propsPreview.geometry(id,{species,ownerScale:1}),b=propsPreview.geometry(id,{species,ownerScale:.3});return a.every((x,i)=>Math.abs(b[i]-x*(i%8<3?.3:1))<1e-5);},{id,species});
     if(!scaled)throw Error('Actor/effect scale mismatch '+id);
     await page.evaluate(()=>propsPreview.setParameter('ownerScale',1));
    }
   }
   await page.locator('.close').click();console.log('Rendered '+id);
  }
  if(!quick){
   for(const species of [0,1]){
    const same=await page.evaluate(species=>{const neutral=JSON.stringify(propsPreview.geometry('body_scratch',{species,severity:0}));return ASSETS.filter(a=>a.bodyEffects&&a.id!=='body_leak').every(a=>JSON.stringify(propsPreview.geometry(a.id,{species,severity:0}))===neutral);},species);
    if(!same)throw Error('Zero grade failed to restore neutral actor');
   }
   await page.evaluate(()=>propsPreview.open('body_leak'));
   for(const species of [0,1]){
    await page.evaluate(n=>propsPreview.setParameter('species',n),species);
    for(const mode of [0,1,2,3]){
     await page.evaluate(n=>propsPreview.setParameter('mode',n),mode);
     const counts=[];
     for(const q of [0,1,4,12]){
      await page.evaluate(n=>propsPreview.setParameter('quantity',n),q);
      counts.push(await page.evaluate(()=>propsPreview.geometry('body_leak',propsPreview.state().parameters).length));
      await page.locator('#view').screenshot({path:path.join(out,`leak-${species}-mode-${mode}-units-${q}.png`)});
     }
     if(!counts.every((n,i)=>i===0||n>counts[i-1]))throw Error('Leaked units mismatch');
    }
   }
   await page.evaluate(()=>{propsPreview.setParameter('species',0);propsPreview.setParameter('mode',2);propsPreview.setParameter('quantity',4);});
   const frames=[];
   for(const t of [0,.5,1]){
    await page.evaluate(t=>propsPreview.setParameter('time',t),t);frames.push(await page.evaluate(()=>JSON.stringify(propsPreview.geometry('body_leak',propsPreview.state().parameters))));
    await page.locator('#view').screenshot({path:path.join(out,`flight-time-${t}.png`)});
   }
   if(new Set(frames).size!==3)throw Error('Flight timeline did not change geometry');
   await page.locator('#body-play').click();await page.waitForTimeout(1900);
   if((await page.evaluate(()=>propsPreview.state())).parameters.time<.99)throw Error('Flight playback did not reach landing');
   await page.keyboard.press('Escape');
   if(await page.locator('#detail').evaluate(e=>e.open))throw Error('Escape close failed');
   await page.locator('#search').fill('油霉');if(await page.locator('.card').count()!==1)throw Error('Mold search failed');
   await page.locator('#search').fill('');
   const approved=await page.evaluate(()=>ASSETS.filter(a=>a.confirmation?.status==='user_approved').map(a=>a.id).sort());
   if(approved.join(',')!=='house_egg,house_egg_cracked')throw Error('Egg approval changed');
   await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'mobile.png'),fullPage:true});
   if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('Mobile horizontal overflow');
   report.checks.push('two actors; full/front/side/back default scenes','four distinct graded states and neutral restoration','actor and effects scale together at .3/1.2','four leak modes, 0/1/4/12 units','flight start/mid/landing and playback','search and Escape','approved egg fingerprints still accepted','390px layout');
  }
  report.checks.push('eight body-effect cards');report.ok=!report.errors.length;
 } finally {
  fs.writeFileSync(path.join(__dirname,'reports/current/body-browser.json'),JSON.stringify(report,null,2));await browser.close();
 }
 if(report.errors.length)throw Error(report.errors.join('\n'));
 console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exitCode=1;});
