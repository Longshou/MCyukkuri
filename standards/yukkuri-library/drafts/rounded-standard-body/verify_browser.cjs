/* Open the delivered file page in Chrome and exercise its actual controls. */
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'../../../..');
const {chromium}=require(path.join(root,'tools/browser-check/node_modules/playwright-core'));
const fingerprint=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
(async()=>{
 const out=path.join(__dirname,'previews/current');
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1300,height:1100}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('requestfailed',r=>errors.push(r.url()));
  await page.goto('file:///'+path.join(out,'index.html').replaceAll('\\','/'));
  await page.waitForFunction(()=>window.PREVIEW_API?.ready);
  const checks=[];
  for(const mode of ['pair','reimu','marisa']){
   await page.selectOption('#mode',mode);
   for(const action of ['idle','waddle','hop','stretch','side_bend','startle']){
    await page.click(`[data-action="${action}"]`);
    await page.evaluate(()=>PREVIEW_API.setState({playing:false,time:0}));
    const before=await page.locator('#view').evaluate(c=>c.toDataURL());
    await page.evaluate(({action})=>PREVIEW_API.setState({time:MODELS.reimu.model.clips[action].duration*.25}),{action});
    const after=await page.locator('#view').evaluate(c=>c.toDataURL());
    checks.push({mode,action,rendered:before.length>10000,changes:before!==after,passed:before.length>10000&&(action==='idle'||before!==after)});
   }
  }
  for(const expr of ['neutral','happy','angry','surprise','sleep']){
   await page.selectOption('#expression',expr);checks.push({expression:expr,passed:await page.locator('#view').evaluate(c=>c.toDataURL().length>10000)});
  }
  await page.evaluate(()=>PREVIEW_API.setState({mode:'pair',expression:'neutral',action:'side_bend',playing:false,time:.59}));
  await page.screenshot({path:path.join(out,'browser-side-bend.png')});
  await page.evaluate(()=>PREVIEW_API.setState({mode:'pair',action:'waddle',playing:false,time:.3}));
  await page.screenshot({path:path.join(out,'browser-preview.png')});
  const front=await page.locator('#view').evaluate(c=>c.toDataURL());
  await page.click('[data-angle="1.570796"]');
  checks.push({view:'side',passed:front!==await page.locator('#view').evaluate(c=>c.toDataURL())});
  await page.check('#bodyOnly');
  checks.push({bodyOnly:true,passed:await page.evaluate(()=>PREVIEW_API.state.bodyOnly)});
  await page.click('#reset');
  checks.push({reset:true,passed:await page.evaluate(()=>PREVIEW_API.state.action==='idle'&&!PREVIEW_API.state.bodyOnly&&PREVIEW_API.state.time===0)});
  checks.push({anatomyDefault:true,passed:await page.evaluate(()=>standardAnatomy.state().amount===0&&standardAnatomy.state().phase==='retracted')});
  await page.selectOption('#mode','pair');await page.click('#reset');await page.click('#anatomy-front');
  await page.click('#anatomy-extend');
  await page.waitForFunction(()=>standardAnatomy.state().phase==='extended');
  checks.push({anatomyExtendsWhilePaused:true,passed:await page.evaluate(()=>PREVIEW_API.state.time===0&&!PREVIEW_API.state.playing&&standardAnatomy.state().part==='peni'&&standardAnatomy.state().amount===1)});
  await page.evaluate(()=>PREVIEW_API.paint());await page.screenshot({path:path.join(out,'browser-anatomy-front.png'),fullPage:true});
  await page.selectOption('#anatomy-part','mamu');
  checks.push({anatomySwitchRetractsFirst:true,passed:await page.evaluate(()=>standardAnatomy.state().phase==='switching'&&standardAnatomy.state().part==='peni')});
  await page.waitForFunction(()=>standardAnatomy.state().phase==='extended'&&standardAnatomy.state().part==='mamu');
  await page.click('#anatomy-rear');await page.evaluate(()=>PREVIEW_API.paint());
  await page.screenshot({path:path.join(out,'browser-anatomy-rear.png'),fullPage:true});
  await page.click('[data-action="waddle"]');
  checks.push({anatomyHeldDuringWalking:true,passed:await page.evaluate(()=>standardAnatomy.state().part==='mamu'&&standardAnatomy.state().amount===1&&PREVIEW_API.state.action==='waddle')});
  await page.click('#anatomy-retract');await page.waitForFunction(()=>standardAnatomy.state().phase==='retracted');
  checks.push({anatomyRetracted:true,passed:await page.evaluate(()=>standardAnatomy.state().amount===0)});
  await page.click('#anatomy-extend');await page.waitForFunction(()=>standardAnatomy.state().phase==='extended');
  await page.selectOption('#mode','reimu');checks.push({anatomyModelChangeReset:true,passed:await page.evaluate(()=>standardAnatomy.state().amount===0)});
  await page.click('#anatomy-extend');await page.waitForFunction(()=>standardAnatomy.state().phase==='extended');
  await page.click('#reset');checks.push({anatomyReset:true,passed:await page.evaluate(()=>standardAnatomy.state().amount===0&&standardAnatomy.state().phase==='retracted')});
  await page.click('#anatomy-extend');await page.waitForFunction(()=>standardAnatomy.state().phase==='extended');
  await page.click('[data-action="birth"]');await page.evaluate(()=>PREVIEW_API.setState({playing:false,time:0}));
  checks.push({birthClearsAndLocksParts:true,passed:await page.evaluate(()=>standardAnatomy.state().amount===0&&document.getElementById('anatomy-extend').disabled&&document.getElementById('anatomy-part').disabled&&PREVIEW_API.state.mode==='pair')});
  await page.evaluate(()=>standardAnatomy.extend('peni'));
  checks.push({birthRejectsPeni:true,passed:await page.evaluate(()=>!standardAnatomy.state().targetExtended&&standardAnatomy.state().amount===0)});
  const birthInitial=await page.locator('#view').evaluate(c=>c.toDataURL());
  await page.evaluate(()=>PREVIEW_API.setState({time:9}));
  checks.push({birthPregnancyVisible:true,passed:birthInitial!==await page.locator('#view').evaluate(c=>c.toDataURL())});
  await page.evaluate(()=>{const slider=document.getElementById('time');slider.value='14.9';slider.dispatchEvent(new Event('input',{bubbles:true}));});
  const emergence=await page.locator('#view').evaluate(c=>c.toDataURL());
  await page.screenshot({path:path.join(out,'browser-birth.png'),fullPage:true});
  checks.push({birthTimelineSeek:true,passed:await page.evaluate(()=>PREVIEW_API.state.time===14.9&&!PREVIEW_API.state.playing&&document.getElementById('birth-status').textContent.includes('依次产出'))});
  await page.evaluate(()=>PREVIEW_API.setState({time:32}));
  checks.push({birthRecovery:true,passed:await page.evaluate(()=>RoundedBirth.sample(MODELS.reimu.model,32,3).belly===0&&document.getElementById('birth-status').textContent.includes('3 / 3'))});
  await page.evaluate(()=>PREVIEW_API.setState({time:14.9}));
  checks.push({birthSeekRepeatable:true,passed:emergence===await page.locator('#view').evaluate(c=>c.toDataURL())});
  for(const count of ['1','5']){
   await page.selectOption('#birth-count',count);await page.evaluate(()=>PREVIEW_API.setState({playing:false,time:32}));
   checks.push({birthCount:count,passed:await page.evaluate(count=>document.getElementById('birth-status').textContent.includes(count+' / '+count),count)});
  }
  await page.selectOption('#birth-count','3');await page.evaluate(()=>PREVIEW_API.setState({playing:false,time:32}));
  await page.click('#play');
  checks.push({birthReplay:true,passed:await page.evaluate(()=>PREVIEW_API.state.time<2&&PREVIEW_API.state.playing)});
  await page.click('#reset');
  checks.push({birthExitResets:true,passed:await page.evaluate(()=>PREVIEW_API.state.action==='idle'&&standardAnatomy.state().amount===0&&!document.getElementById('anatomy-part').disabled)});
  await page.click('#anatomy-extend');await page.waitForFunction(()=>standardAnatomy.state().phase==='extended');
  await page.reload();await page.waitForFunction(()=>window.PREVIEW_API?.ready);
  checks.push({reopen:true,passed:await page.evaluate(()=>PREVIEW_API.state.mode==='pair'&&PREVIEW_API.state.action==='waddle'&&!PREVIEW_API.state.bodyOnly&&standardAnatomy.state().amount===0)});
  const report={createdAt:new Date().toISOString(),browser:await browser.version(),inputs:Object.fromEntries(['index.html','viewer.js','pose.js','birth.js','preview-data.js'].map(p=>[p,fingerprint(path.join(out,p))])),errors,checks};
  fs.writeFileSync(path.join(__dirname,'reports/current/browser.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({errors,checks:checks.length,failed:checks.filter(c=>!c.passed)}));
  if(errors.length||checks.some(c=>!c.passed))process.exitCode=1;
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
