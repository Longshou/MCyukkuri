const fs=require('fs'),path=require('path'),{chromium}=require(path.resolve('tools/browser-check/node_modules/playwright-core'));
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const base=path.resolve('art/source/hat-interactions'),errors=[],checks=[];
 try{
  const page=await browser.newPage({viewport:{width:1200,height:1000}});page.on('pageerror',e=>errors.push(e.message));page.on('requestfailed',r=>errors.push(r.url()));
  await page.goto('file:///'+path.join(base,'previews/current/index.html').replaceAll('\\','/'));
  await page.waitForFunction(()=>Object.keys(textures).length===5);await page.evaluate(()=>playing=false);
  for(const id of ['marisa','marisa_sparse','marisa_face','marisa_bean','house_marisa']){
   const result=await page.evaluate(id=>{
    subject.value=id;const states=[];
    for(const state of ['worn','bare','remove','return','carry'])for(const phase of [0,.5,1]){
     if(id==='house_marisa'&&state==='carry')continue;
     mode.value=state;q=phase;paint();let pixels=ctx.getImageData(0,0,700,650).data,visible=0;
     for(let i=3;i<pixels.length;i+=4)if(pixels[i]>128)visible++;
     states.push({state,phase,visible});
    }
    return {id,states,passed:states.every(s=>s.visible>10000)};
   },id);checks.push(result);
  }
  await page.selectOption('#subject','marisa_sparse');await page.selectOption('#mode','bare');await page.evaluate(()=>{q=.5;paint();});
  const out=path.join(base,'reports/current');fs.mkdirSync(out,{recursive:true});await page.screenshot({path:path.join(out,'browser.png')});
  const report={passed:!errors.length&&checks.every(c=>c.passed),errors,checks};fs.writeFileSync(path.join(out,'browser.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({passed:report.passed,poseStates:checks.reduce((n,c)=>n+c.states.length,0),errors}));if(!report.passed)process.exitCode=1;
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
