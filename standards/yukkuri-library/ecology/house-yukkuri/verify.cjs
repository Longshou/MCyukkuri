const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_CORE_PATH||path.resolve(__dirname,'../../../../tools/browser-check/node_modules/playwright-core'));
const lifecycle=require('./lifecycle.js');
const direct=lifecycle.snapshot(18),stepped=lifecycle.create();
for(let t=0;t<=180;t++)lifecycle.advance(stepped,t/10);
assert.deepEqual(direct,stepped,'Frame rate must not affect births');
assert.equal(direct.young.length,3);lifecycle.advance(direct,22);assert.equal(direct.young.length,3);
const before=lifecycle.snapshot(8),removed=lifecycle.snapshot(18,8);
assert.equal(before.young.length,1);assert.equal(removed.young.length,1);
assert.equal(removed.young[0].status,'alive');assert.equal(removed.young[0].nestId,null);
assert.equal(removed.eggs.filter(e=>e.status==='lost').length,2);
assert.equal(lifecycle.snapshot(18,0).young.length,0);
assert.equal(lifecycle.snapshot(18,0).eggs.filter(e=>e.status==='lost').length,0,'Unlaid eggs cannot be lost');
assert.deepEqual(JSON.parse(JSON.stringify(removed)),removed);
assert.throws(()=>lifecycle.advance(removed,-1));
(async()=>{
 const b=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const errors=[],checks=[];
 try{
  const p=await b.newPage({viewport:{width:1280,height:1100}});p.on('pageerror',e=>errors.push(e.message));
  await p.goto(pathToFileURL(path.join(__dirname,'index.html')).href);await p.waitForFunction(()=>window.houseDebug?.ready());
  await p.evaluate(()=>houseDebug.setTime(0));
  await p.screenshot({path:path.join(__dirname,'overview.png'),fullPage:true});
  for(const subject of ['pair','marisa','reimu','compare','nest','family']){
   await p.locator('#subject').selectOption(subject);
   for(const [view,az] of [['front','0'],['side','1.5708'],['back','3.14159']]){
    await p.locator(`[data-angle="${az}"]`).click();
    if(subject!=='family')await p.locator('canvas').screenshot({path:path.join(__dirname,`${subject}-${view}.png`)});
   }
   checks.push(subject+' front/side/back');
  }
  await p.locator('#resetCamera').click();
  for(const action of ['rest','forage','build','flee','hatch']){
   await p.locator('#action').selectOption(action);
   for(const t of [0,4,8,12,18])await p.evaluate(t=>houseDebug.setTime(t),t);
   await p.locator('canvas').screenshot({path:path.join(__dirname,action+'.png')});
   checks.push(action+' 5 times');
  }
  await p.evaluate(()=>houseDebug.setTime(8));await p.locator('#removeNest').click();await p.evaluate(()=>houseDebug.setTime(18));
  assert.equal((await p.evaluate(()=>houseDebug.snapshot())).young.length,1);
  await p.locator('#time').fill('5');await p.locator('#time').dispatchEvent('input');
  assert.equal((await p.evaluate(()=>houseDebug.snapshot())).nest.intact,true);
  await p.locator('#restore').click();await p.evaluate(()=>houseDebug.setTime(0));
  const angle=await p.evaluate(()=>houseDebug.state().az);
  const box=await p.locator('canvas').boundingBox();await p.mouse.move(box.x+200,box.y+200);await p.mouse.down();await p.mouse.move(box.x+270,box.y+230);await p.mouse.up();
  assert.notEqual(await p.evaluate(()=>houseDebug.state().az),angle);
  const span=await p.evaluate(()=>houseDebug.state().span);await p.mouse.wheel(0,200);await p.waitForTimeout(80);
  assert.notEqual(await p.evaluate(()=>houseDebug.state().span),span);
  await p.locator('#play').click();await p.waitForTimeout(250);await p.locator('#play').click();
  assert.ok(await p.evaluate(()=>houseDebug.state().time)>0);
  await p.locator('#subject').selectOption('reimu');await p.locator('#resetCamera').click();
  for(const expression of JSON.parse(fs.readFileSync(path.join(__dirname,'config.json'),'utf8')).expressions){
   await p.locator('#expression').selectOption(expression);
   await p.locator('canvas').screenshot({path:path.join(__dirname,'reimu-'+expression+'.png')});
  }
  await p.locator('#expression').selectOption('auto');await p.locator('#subject').selectOption('family');await p.locator('#action').selectOption('rest');await p.evaluate(()=>houseDebug.setTime(0));await p.locator('#resetCamera').click();
  await p.locator('#subject').selectOption('reimu');
  await p.locator('#expression').selectOption('neutral');
  for(const [part,angle] of [['peni','0'],['mamu','3.14159']]){
   await p.locator(`[data-angle="${angle}"]`).click();
   await p.locator('#anatomyPart').selectOption(part);await p.locator('#extendPart').click();
   await p.waitForFunction(()=>houseDebug.anatomy().phase==='extended');
   assert.equal((await p.evaluate(()=>houseDebug.anatomy())).part,part);
   assert.equal((await p.evaluate(()=>houseDebug.state())).playing,false);
   await p.waitForTimeout(100);
   await p.locator('canvas').screenshot({path:path.join(__dirname,part+'-extended.png')});
  }
  await p.locator('#retractPart').click();
  await p.waitForFunction(()=>houseDebug.anatomy().phase==='retracted');
  assert.equal((await p.evaluate(()=>houseDebug.anatomy())).amount,0);
  checks.push('adult anatomy extend, mutually exclusive switch and retract while paused');
  await p.locator('#subject').selectOption('family');await p.locator('#resetCamera').click();
  await p.setViewportSize({width:430,height:1100});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await p.screenshot({path:path.join(__dirname,'mobile.png'),fullPage:true});
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(__dirname,'browser-verification.json'),JSON.stringify({checks,frame_rate_independent_births:true,no_duplicate_births:true,young_survive_nest_removal:true,timeline_scrub:true,orbit:true,zoom:true,play_pause:true,expressions:11,mobileOverflow:false,pageErrors:errors},null,2));
  console.log('House models, actions, hatch lifecycle, controls and mobile layout passed.');
 }finally{await b.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
