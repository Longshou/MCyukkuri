const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('../../tools/browser-check/node_modules/playwright-core');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
const p=await b.newPage({viewport:{width:1200,height:940}}),errors=[];p.on('pageerror',e=>errors.push(e.message));await p.goto('file:///'+path.join(__dirname,'preview.html').replaceAll('\\','/'));await p.waitForFunction(()=>document.querySelector('#error').style.display==='none');
const hash=()=>p.evaluate(()=>{const c=document.querySelector('canvas');return c.toDataURL()});
await p.evaluate(()=>waddleDebug.setTime(.3));await p.waitForTimeout(180);const left=await hash();await p.screenshot({path:path.join(__dirname,'browser-preview.png')});
await p.evaluate(()=>waddleDebug.setTime(.9));await p.waitForTimeout(180);assert.notEqual(await hash(),left);
await p.locator('[data-angle="180"]').click();await p.waitForTimeout(150);await p.screenshot({path:path.join(__dirname,'browser-back.png')});
await p.locator('#walk').click();const running=await hash();await p.waitForTimeout(350);assert.notEqual(await hash(),running);
await p.locator('#walk').click();await p.waitForTimeout(150);const paused=await hash();await p.waitForTimeout(180);assert.equal(await hash(),paused);
await p.locator('#travel').click();await p.waitForTimeout(200);assert.equal(await p.evaluate(()=>waddleDebug.state().travel),true);
await p.locator('#rest').click();await p.waitForTimeout(150);assert.deepEqual(await p.evaluate(()=>({e:waddleDebug.state().enabled,t:waddleDebug.state().travel})),{e:false,t:false});
await p.locator('#reset').click();await p.waitForTimeout(150);const base=await hash();await p.mouse.move(600,400);await p.mouse.down();await p.mouse.move(720,460,{steps:4});await p.mouse.up();await p.waitForTimeout(150);assert.notEqual(await hash(),base);
await p.locator('#reset').click();await p.waitForTimeout(150);assert.equal(await hash(),base);await p.mouse.move(600,400);await p.mouse.wheel(0,350);await p.waitForTimeout(150);assert.notEqual(await hash(),base);
await p.locator('#reset').click();await p.evaluate(()=>waddleDebug.setTime(.3));await p.setViewportSize({width:390,height:920});await p.waitForTimeout(180);await p.screenshot({path:path.join(__dirname,'browser-mobile.png')});assert.deepEqual(errors,[]);
fs.writeFileSync(path.join(__dirname,'browser-verification.json'),JSON.stringify({status:'passed',browser:await b.version(),checks:['opposite sway poses differ','back view','play / pause','forward demonstration','rest pose','drag / reset / zoom','mobile viewport'],errors},null,2));console.log('PASS real browser animation and controls');
}finally{await b.close()}})().catch(e=>{console.error(e);process.exit(1)});
