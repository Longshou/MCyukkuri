const {chromium}=require('../tools/browser-check/node_modules/playwright-core');
const assert=require('assert');
(async()=>{
 const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const p=await b.newPage({viewport:{width:1500,height:1300}});let errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.goto('file:///E:/yukkuri/MCyukkuri/standards/yukkuri-library/batch-16-40.html');
 const frames=p.frames().filter(f=>f!==p.mainFrame());
 await Promise.all(frames.map(f=>f.waitForFunction(()=>window.actionsDebug?.ready())));
 const states=()=>Promise.all(frames.map(f=>f.evaluate(()=>window.actionsDebug.state())));
 const options=await p.locator('#expression option').allTextContents();assert.equal(options.length,9);
 let paired=0;
 for(let i=0;i<9;i++){
  await p.selectOption('#expression',String(i));await p.waitForTimeout(180);
  let s=await states();
  assert(s.every(x=>x.expression===s[0].expression&&x.trialPartCount===10));
  if(await p.locator('#pairCompare').isVisible()){
   await p.click('#pairCompare');await p.waitForTimeout(180);
   s=await states();
   assert.notEqual(s[0].expression,s[2].expression);paired++;
   if(i===8)await p.screenshot({path:'temp/round2-pair-models.png',fullPage:true});
   await p.click('#pairCompare');
  }
 }
 await p.selectOption('#expression','5');await p.waitForTimeout(220);await p.screenshot({path:'temp/round2-dull-models.png',fullPage:true});
 assert.deepEqual(errors,[]);console.log(JSON.stringify({entries:9,paired,errors}));await b.close();
})().catch(e=>{console.error(e);process.exit(1)});
