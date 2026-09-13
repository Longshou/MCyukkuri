const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const crypto=require('node:crypto');
const {chromium}=require(path.resolve(__dirname,'../../../../tools/browser-check/node_modules/playwright-core'));
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:1000}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(pathToFileURL(path.join(__dirname,'previews/current/index.html')).href+'?v=8');
  const frame=page.frames().find(f=>f!==page.mainFrame());
  await frame.waitForFunction(()=>window.houseDebug?.ready());
  const initial=await frame.evaluate(()=>({version:DATA.config.design,badge:document.querySelector('.badge').textContent,anatomy:houseDebug.anatomy()}));
  const checks=[];
  for(const subject of ['reimu','marisa','pair','family','compare','nest']){
   await frame.locator('#subject').selectOption(subject);
   await frame.locator('[data-angle="3.14159"]').click();
   await frame.evaluate(()=>houseDebug.setTime(0));
   const equal=await frame.evaluate(()=>{
    const before=document.getElementById('view').toDataURL();
    const saved=Object.values(DATA.characters).map(m=>m.vertices.slice());
    for(const model of Object.values(DATA.characters)){
     let start=0;
     for(let e=0;e<model.rig.groups.length;e++){
      const end=start+model.rig.element_vertex_counts[e]*8;
      if(model.rig.groups[e].startsWith('anatomy_'))for(let i=start;i<end;i+=8)model.vertices[i+1]-=10000;
      start=end;
     }
    }
    houseDebug.render();const after=document.getElementById('view').toDataURL();
    Object.values(DATA.characters).forEach((m,i)=>m.vertices=saved[i]);houseDebug.render();
    return before===after;
   });
   checks.push({subject,default_equals_removed_anatomy:equal});assert.ok(equal,subject);
   if(subject==='reimu')await frame.locator('canvas').screenshot({path:path.join(__dirname,'previews/current/reimu-default-browser.png')});
  }
  assert.equal(initial.anatomy.amount,0);assert.equal(initial.anatomy.targetExtended,false);
  const resets=[];
  for(const reset of ['subject','action','restart','restore']){
   await frame.locator('#subject').selectOption('reimu');
   await frame.locator('#anatomyPart').selectOption('mamu');
   await frame.locator('#extendPart').click();
   await frame.waitForFunction(()=>houseDebug.anatomy().amount===1);
   await frame.evaluate(()=>houseDebug.render());
   assert.ok((await frame.locator('#anatomyState').textContent()).includes('已伸出'));
   if(reset==='subject')await frame.locator('#subject').selectOption('pair');
   else if(reset==='action')await frame.locator('#action').selectOption('forage');
   else await frame.locator('#'+reset).click();
   const state=await frame.evaluate(()=>houseDebug.anatomy());
   assert.equal(state.amount,0,reset+' must clear extension immediately');
   assert.equal(state.targetExtended,false);
   assert.equal(await frame.locator('#anatomyState').textContent(),'已收回（常态）');
   resets.push(reset);
  }
  assert.deepEqual(errors,[]);
  const indexHash=sha(path.join(__dirname,'index.html'));
  assert.ok(frame.url().includes('?rev='+indexHash.slice(0,12)),'iframe must carry content revision');
  const report={executed_utc:new Date().toISOString(),environment:{node:process.version,browser:await browser.version(),platform:process.platform},initial,checks,immediate_retraction_after:resets,errors,index_sha256:indexHash,screenshot_sha256:sha(path.join(__dirname,'previews/current/reimu-default-browser.png')),scope:'Fresh browser UI; six subjects at rest compared pixel-for-pixel with anatomy removed, then extend and switch/reset interactions.'};
  fs.writeFileSync(path.join(__dirname,'reports/current/default-browser.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
