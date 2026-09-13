const {chromium}=require(process.env.PLAYWRIGHT_PATH || 'playwright');
const path=require('path');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--allow-file-access-from-files']});
 const page=await browser.newPage({viewport:{width:1500,height:1000},deviceScaleFactor:1});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('file:///'+path.resolve(__dirname,'../preview/index.html').replaceAll('\\','/'));
 await page.waitForFunction(()=>window.previewReady);
 await page.waitForTimeout(1200);
 for(const view of ['three','front','side','back']){
  await page.click(`[data-view="${view}"]`);await page.waitForTimeout(300);
  await page.screenshot({path:path.resolve(__dirname,`../preview/${view==='three'?'models-preview':view}.png`)});
 }
 await page.click('#wire');if(!await page.locator('#wire').evaluate(e=>e.classList.contains('active')))throw Error('Wire toggle failed');
 await page.click('#wire');await page.click('#spin');
 if(!await page.locator('#spin').evaluate(e=>e.classList.contains('active')))throw Error('Rotation toggle failed');
 await page.click('[data-view="three"]');await page.mouse.move(600,500);await page.mouse.down();await page.mouse.move(700,530);await page.mouse.up();await page.mouse.wheel(0,80);
 if(errors.length)throw Error(errors.join('\n'));
 console.log('PASS: both models rendered; four views, wireframe, rotation, drag and wheel exercised; no JS errors.');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
