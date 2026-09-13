const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('tools/browser-check/node_modules/playwright-core'));
(async()=>{
 const out=path.resolve('docs/validation/personality-deibu/current');fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[],checks=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('requestfailed',r=>errors.push(r.url()));
  await page.goto('file:///'+path.resolve('art/source/rounded-runtime/previews/current/index.html').replaceAll('\\','/'));
  await page.waitForFunction(()=>Object.keys(textures).length>=160);await page.evaluate(()=>playing=false);
  const ids=await page.evaluate(()=>Object.keys(MODELS));
  for(const id of ids){
   await page.selectOption('#subject',id);
   for(const expression of ['neutral','angry','happy']){
    await page.selectOption('#expression',expression);
    const result=await page.evaluate(()=>{
     const model=MODELS[$('subject').value].model,poses=[];
     for(const action of ['idle','waddle','side_bend','stretch','taiso'])for(const q of [.25,.75]){
      const data=posed(model,action,model.clips[action].duration*q);poses.push(data.length>0&&data.every(Number.isFinite));
     }
     // A nonempty PNG can contain only a few face pixels: compare actual opacity with the full mesh silhouette.
     const ground=$('ground').checked;$('ground').checked=false;
     const texture=textures[$('subject').value+'/'+$('expression').value],opaque=new Uint8ClampedArray(texture.length).fill(255),coverage=[];
     for(const angle of [0,Math.PI/2,Math.PI]){
      az=angle;paint();const actual=ctx.getImageData(0,0,canvas.width,canvas.height).data;
      const expected=renderModel(posed(model,$('action').value,time),opaque,canvas.width,canvas.height,az,el,framing[$('subject').value].span*zoom,{centerY:framing[$('subject').value].center,textureSize:model.texture_size});
      let visible=0,silhouette=0;for(let i=3;i<actual.length;i+=4){if(actual[i]>=128)visible++;if(expected[i]>=128)silhouette++;}
      coverage.push({angle,visible,silhouette,fraction:visible/Math.max(1,silhouette)});
     }
     $('ground').checked=ground;az=.3;paint();return {finite:poses.every(Boolean),pixels:coverage.every(c=>c.silhouette>1000&&c.fraction>.8),coverage};
    });checks.push({id,expression,...result});
   }
  }
  await page.selectOption('#subject','reimu_deibu');await page.selectOption('#expression','neutral');await page.screenshot({path:path.join(out,'browser.png')});
  const frames=path.join(out,'browser-models');fs.mkdirSync(frames,{recursive:true});
  for(const id of ids){await page.selectOption('#subject',id);await page.locator('#view').screenshot({path:path.join(frames,id+'.png')});}
  const result={passed:!errors.length&&checks.every(r=>r.finite&&r.pixels),errors,checks};fs.writeFileSync(path.join(out,'browser.json'),JSON.stringify(result,null,2));
  console.log(JSON.stringify({passed:result.passed,checks:checks.length,errors}));if(!result.passed)process.exitCode=1;
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
