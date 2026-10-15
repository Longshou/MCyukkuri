/* Verify the delivered page, real atlas dimensions, pose states and visible pixels. */
const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('tools/browser-check/node_modules/playwright-core'));
(async()=>{
 const species=process.argv[2]||'marisa';
 const base=path.resolve('standards/yukkuri-library/subspecies/deficient/rounded-'+species);
 const out=path.join(base,'reports/current'),checks=[],errors=[];fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:900}});
  page.on('pageerror',e=>errors.push(e.message));page.on('requestfailed',r=>errors.push(r.url()));
  await page.goto('file:///'+path.join(base,'previews/current/index.html').replaceAll('\\','/'));
  await page.waitForFunction(()=>Object.keys(textures).length>=32);
  await page.evaluate(()=>playing=false);
  for(const id of [species+'_sparse',species+'_face']){
   await page.selectOption('#subject',id);
   const expressions=await page.locator('#expression option').evaluateAll(es=>es.map(e=>e.value));
   for(const expression of expressions){
    await page.selectOption('#expression',expression);
    const result=await page.evaluate(()=>{
     const m=MODELS[$('subject').value].model,tex=textures[$('subject').value+'/'+$('expression').value];
     const states=[];$('ground').checked=false;
     for(const action of ['idle','waddle','side_bend','stretch_shape'])for(const phase of [0,.25,.5,.75,1]){
      $('action').value=action;time=m.clips[action].duration*phase;az=.55;paint();
      const pixels=ctx.getImageData(0,0,canvas.width,canvas.height).data;
      let visible=0;for(let i=3;i<pixels.length;i+=4)if(pixels[i]>128)visible++;
      states.push({action,phase,visible});
     }
     return {atlas:tex.length===m.texture_size*m.texture_size*4,states,visible:states.every(s=>s.visible>4000)};
    });
    checks.push({id,expression,...result});
   }
   await page.selectOption('#expression','neutral');await page.selectOption('#action','waddle');
   await page.evaluate(()=>{time=.3;az=.55;paint();});
   await page.screenshot({path:path.join(out,id+'-browser.png')});
  }
  const passed=!errors.length&&checks.every(c=>c.atlas&&c.visible);
  fs.writeFileSync(path.join(out,'browser.json'),JSON.stringify({passed,errors,checks},null,2));
  console.log(JSON.stringify({passed,expressions:checks.length,pose_states:checks.length*20,errors}));
  if(!passed)process.exitCode=1;
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
