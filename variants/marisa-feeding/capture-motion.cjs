const {chromium}=require('../../tools/browser-check/node_modules/playwright-core');
const path=require('node:path'),fs=require('node:fs');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--allow-file-access-from-files','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const page=await browser.newPage({viewport:{width:900,height:650}});
 await page.goto('file:///'+path.join(__dirname,'preview.html').replaceAll('\\','/'));await page.waitForFunction(()=>window.feedingPreview);
 await page.addStyleTag({content:'header,aside,footer,.scene-title,.camera,.hint{display:none!important}main{display:block;padding:0}.stage{height:650px;min-height:650px;border:0;border-radius:0}.speech{top:30px}'});
 await page.evaluate(()=>{const p=feedingPreview;p.sim.paused=true;p.sim.fill();p.sim.start();p.draw();});
 await page.waitForTimeout(200);
 const frames=path.join(__dirname,'render-frames');fs.mkdirSync(frames,{recursive:true});
 for(let i=0;i<138;i++){
  await page.evaluate(()=>{const p=feedingPreview;p.sim.paused=false;p.advance(.08);p.sim.paused=true;p.draw();});
  await page.locator('.stage').screenshot({path:path.join(frames,String(i).padStart(3,'0')+'.png')});
 }
 await page.evaluate(()=>{const p=feedingPreview;p.sim.reset();p.sim.fill();p.sim.start();p.sim.update(4.5);p.sim.paused=true;p.setCamera(Math.PI/2,.08);});
 await page.locator('.stage').screenshot({path:path.join(__dirname,'contact-side.png')});
 await page.evaluate(()=>{const p=feedingPreview;p.sim.paused=false;p.sim.update(2.18);p.sim.paused=true;p.setCamera(.78,.37);});
 await page.locator('.stage').screenshot({path:path.join(__dirname,'recovery.png')});
 await browser.close();console.log('Captured 138 frames, contact and recovery.');
})().catch(e=>{console.error(e);process.exit(1);});
