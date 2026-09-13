const {chromium}=require('../../tools/browser-check/node_modules/playwright-core');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1200,height:900}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto('file:///'+path.join(__dirname,'preview.html').replaceAll('\\','/'));
  await page.waitForFunction(()=>document.querySelector('#error').style.display==='none');
  const fingerprint=()=>page.evaluate(()=>{const c=document.querySelector('canvas'),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let sum=0;for(let i=0;i<d.length;i++)sum=(sum*31+d[i])>>>0;return sum;});
  const checks=[];
  for(const [angle,name] of [['0','front'],['-32','quarter'],['90','side'],['180','back']]){
   await page.locator(`[data-angle="${angle}"]`).click();await page.waitForTimeout(180);
   await page.screenshot({path:path.join(__dirname,`browser-${name}.png`)});checks.push({view:name,hash:await fingerprint()});
  }
  assert.equal(new Set(checks.map(c=>c.hash)).size,4);
  await page.locator('#reset').click();await page.waitForTimeout(180);const base=await fingerprint();
  await page.mouse.move(600,420);await page.mouse.down();await page.mouse.move(700,470,{steps:5});await page.mouse.up();await page.waitForTimeout(180);assert.notEqual(await fingerprint(),base);
  await page.locator('#reset').click();await page.waitForTimeout(180);assert.equal(await fingerprint(),base);
  await page.mouse.move(600,420);await page.mouse.wheel(0,400);await page.waitForTimeout(180);assert.notEqual(await fingerprint(),base);
  await page.locator('#auto').click();const start=await fingerprint();await page.waitForTimeout(400);assert.notEqual(await fingerprint(),start);await page.locator('#auto').click();
  await page.locator('#reset').click();await page.waitForTimeout(180);await page.screenshot({path:path.join(__dirname,'browser-preview.png')});
  await page.setViewportSize({width:390,height:844});await page.waitForTimeout(200);assert.equal(await page.locator('#error').isVisible(),false);await page.screenshot({path:path.join(__dirname,'browser-mobile.png')});
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(__dirname,'browser-verification.json'),JSON.stringify({status:'passed',browser:await browser.version(),checks,interaction:['drag','reset','zoom','auto rotation','mobile render'],errors},null,2));
  console.log('PASS Chrome actual canvas render: four views, drag, reset, zoom, auto rotation, mobile, no page errors');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});

