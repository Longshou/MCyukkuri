const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'../../..'),lib=path.resolve(__dirname,'..'),out=path.join(__dirname,'previews/current');
const {chromium}=require(path.join(root,'tools/browser-check/node_modules/playwright-core'));
const cfg=JSON.parse(fs.readFileSync(path.join(__dirname,'recipe.json'))),checks=[],errors=[];
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1250,height:1100}});
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto('file:///'+path.join(out,'index.html').replaceAll('\\','/'));
  await page.waitForFunction(()=>window.PREVIEW_API?.ready,{},{timeout:120000});
  for(const id of cfg.characters){
   for(const action of ['idle','waddle','birth']){
    const result=await page.evaluate(({id,action})=>{const api=PREVIEW_API;api.setState({mode:id,action,time:action==='birth'?13:.3,playing:false,bodyOnly:false});const c=document.getElementById('view'),p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let pixels=0;for(let i=3;i<p.length;i+=4)if(p[i])pixels++;return {pixels,state:{...api.state}};},{id,action});
    checks.push({id,entry:'gallery',action,passed:result.pixels>1000,...result});
   }
   await page.evaluate(id=>{PREVIEW_API.setState({mode:id,action:'idle',time:0,playing:false});standardAnatomy.extend('peni');PREVIEW_API.advanceAnatomy(1);},id);
   const part=await page.evaluate(()=>standardAnatomy.state());checks.push({id,entry:'gallery',action:'extend',passed:part.amount===1});
   await page.evaluate(()=>{standardAnatomy.selectPart('mamu');PREVIEW_API.advanceAnatomy(1);});
   const switched=await page.evaluate(()=>standardAnatomy.state());checks.push({id,entry:'gallery',action:'switch',passed:switched.part==='mamu'&&switched.amount===1});
   await page.evaluate(()=>{standardAnatomy.retract();PREVIEW_API.advanceAnatomy(1);});
  }
  await page.evaluate(()=>PREVIEW_API.setState({mode:'reimu_wasa',action:'idle',playing:false,time:0}));
  await page.screenshot({path:path.join(out,'browser-preview.png'),fullPage:true});
  for(const id of cfg.characters){
   await page.goto('file:///'+path.join(lib,cfg.sources[id].export,'index.html').replaceAll('\\','/'));
   await page.waitForFunction(()=>window.actionsDebug?.ready(),{},{timeout:120000});
   for(const expression of ['neutral','angry','puff']){
    await page.evaluate(e=>{actionsDebug.setTime(.3);actionsDebug.setExpression(e);},expression);
    await page.waitForTimeout(130);
    const pixels=await page.evaluate(()=>{const c=document.querySelector('canvas'),p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;for(let i=3;i<p.length;i+=4)if(p[i])n++;return n;});
    checks.push({id,entry:'full',expression,passed:pixels>1000,pixels});
   }
  }
 }finally{await browser.close();}
 const failures=checks.filter(c=>!c.passed),report={createdAt:new Date().toISOString(),status:failures.length||errors.length?'failed':'passed',inputs:{manifest:hash(path.join(__dirname,'manifest.json')),viewer:hash(path.join(out,'viewer.js'))},checks,failures,errors};
 fs.writeFileSync(path.join(__dirname,'reports/current/browser.json'),JSON.stringify(report,null,2));
 console.log(JSON.stringify({checks:checks.length,failures,errors}));if(failures.length||errors.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
