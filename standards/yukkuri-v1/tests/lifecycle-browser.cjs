const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const {chromium}=require('../../../tools/browser-check/node_modules/playwright-core');
const root=path.resolve(__dirname,'..'),out=path.join(root,'lifecycle');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const report={errors:[],screenshots:[],checks:[]};
 try{
  const page=await browser.newPage({viewport:{width:1200,height:1100}});
  page.on('pageerror',e=>report.errors.push(e.message));
  await page.goto(pathToFileURL(path.join(out,'index.html')).href);
  await page.waitForFunction(()=>window.lifecycleDebug?.ready());
  await page.locator('#count').selectOption('3');
  const shot=async(name)=>{await page.waitForTimeout(100);await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});report.screenshots.push(name+'.png');};
  for(const [name,t] of [['stem-sprout',2],['stem-young',7],['stem-mature',19],['stem-falling',21.6],['stem-complete',28]]){
   await page.evaluate(t=>lifecycleDebug.setTime(t),t);await shot(name);
  }
  await page.evaluate(()=>lifecycleDebug.setTime(19));
  for(const [name,angle] of [['stem-front','0'],['stem-side','1.570796'],['stem-back','3.141593']]){await page.locator('[data-angle="'+angle+'"]').click();await shot(name);}
  await page.locator('#reset-camera').click();
  for(const count of [1,2,3,4,5]){
   await page.locator('#count').selectOption(String(count));
   await page.evaluate(()=>lifecycleDebug.setTime(19));
   const s=await page.evaluate(()=>lifecycleDebug.state().sample);
   assert.equal(s.children.length,count);assert(s.children.every(c=>c.sleeping&&c.attached));
   assert.equal(await page.locator('#birth-cards .card:visible').count(),count);
   await shot('stem-count-'+count);
  }
  report.checks.push('1–5 offspring counts with closed eyes and no extra child cards');
  await page.locator('#save').click();
  await page.evaluate(()=>lifecycleDebug.setTime(1));await page.locator('#load').click();
  assert.equal(await page.evaluate(()=>lifecycleDebug.state().time),19);
  await page.reload();await page.waitForFunction(()=>window.lifecycleDebug?.ready());await page.locator('#load').click();
  assert.equal(await page.evaluate(()=>lifecycleDebug.state().time),19);report.checks.push('save and reload restores timeline and camera');
  assert.equal(await page.evaluate(()=>lifecycleDebug.state().count),5);report.checks.push('offspring count persists through reload');
  await page.locator('[data-mode="growth"]').click();await shot('growth-ranges');
  assert.equal(await page.locator('#birth-cards').isVisible(),false);
  const sliders=page.locator('#growth-cards input');
  for(const value of ['0','1']){
   for(let i=0;i<3;i++)await sliders.nth(i).fill(value);
   await shot('growth-'+(value==='0'?'min':'max'));
  }
  report.checks.push('three independent stage sliders reach both endpoints');
  await page.locator('[data-mode="birth"]').click();
  await page.locator('#random-count').click();
  const chosen=await page.evaluate(()=>lifecycleDebug.state().count);assert(chosen>=1&&chosen<=5);
  await page.waitForTimeout(100);assert.equal(await page.evaluate(()=>lifecycleDebug.state().count),chosen);
  await page.locator('#play').click();await page.locator('#count').selectOption('5');
  await page.evaluate(()=>lifecycleDebug.setTime(27.9));await page.locator('#speed').selectOption('2');await page.locator('#play').click();
  await page.waitForFunction(()=>lifecycleDebug.state().time===28&&!lifecycleDebug.state().playing);
  await page.locator('#play').click();await page.waitForFunction(()=>lifecycleDebug.state().time>0&&lifecycleDebug.state().time<5);await page.locator('#play').click();
  report.checks.push('nonloop playback stops at completion and can restart');
  const before=await page.evaluate(()=>lifecycleDebug.state().az);
  await page.locator('#view').hover();await page.mouse.move(600,440);await page.mouse.down();await page.mouse.move(670,470,{steps:5});await page.mouse.up();
  assert.notEqual(await page.evaluate(()=>lifecycleDebug.state().az),before);
  const oldSpan=await page.evaluate(()=>lifecycleDebug.state().span);await page.mouse.wheel(0,100);await page.waitForTimeout(100);
  assert.notEqual(await page.evaluate(()=>lifecycleDebug.state().span),oldSpan);report.checks.push('paused orbit and zoom');
  await page.locator('#reset-camera').click();await page.evaluate(()=>lifecycleDebug.setTime(19));
  await page.locator('#mother').fill('1.2');await shot('stem-large-parent');
  await page.setViewportSize({width:390,height:844});await shot('stem-mobile');
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  report.checks.push('mobile fits viewport');assert.deepEqual(report.errors,[]);
  fs.writeFileSync(path.join(out,'verification.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
