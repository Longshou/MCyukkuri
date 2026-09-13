const {chromium}=require('../../tools/browser-check/node_modules/playwright-core');
const path=require('node:path');const fs=require('node:fs');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--allow-file-access-from-files','--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const page=await browser.newPage({viewport:{width:1360,height:900},deviceScaleFactor:1});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('file:///'+path.join(__dirname,'preview.html').replaceAll('\\','/'));await page.waitForFunction(()=>window.feedingPreview);await page.waitForTimeout(500);
 await page.evaluate(()=>{const p=feedingPreview;p.sim.paused=true;p.draw();});await page.screenshot({path:path.join(__dirname,'preview-empty.png')});
 await page.click('#fill');
 async function advance(t){await page.evaluate(t=>{const p=feedingPreview;p.sim.paused=false;p.advance(t);p.sim.paused=true;p.draw();},t);}
 await advance(3);await page.screenshot({path:path.join(__dirname,'preview-approach.png')});
 await advance(1.48);await page.screenshot({path:path.join(__dirname,'preview-bite.png')});
 await advance(1.02);await page.screenshot({path:path.join(__dirname,'preview-chew.png')});
 await page.click('[data-view="side"]');await page.waitForTimeout(100);await page.screenshot({path:path.join(__dirname,'preview-side.png')});
 await page.click('[data-view="three"]');await advance(25);await page.screenshot({path:path.join(__dirname,'preview-full.png')});
 const end=await page.evaluate(()=>feedingPreview.snapshot());if(end.state!=='full'||end.food!==0||end.bites!==8)throw Error(JSON.stringify(end));
 await page.click('#reset');await page.setViewportSize({width:390,height:844});await page.waitForTimeout(100);await page.screenshot({path:path.join(__dirname,'preview-mobile.png'),fullPage:true});
 const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);if(overflow)throw Error('Mobile overflow');if(errors.length)throw Error(errors.join('\n'));
 fs.writeFileSync(path.join(__dirname,'browser-report.json'),JSON.stringify({errors,fullMeal:end,mobileOverflow:overflow},null,2));
 console.log('PASS: offline WebGL, fill button, entire meal, reset and 390px layout.');await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
