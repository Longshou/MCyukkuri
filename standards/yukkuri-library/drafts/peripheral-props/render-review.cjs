/* Render the exported triangles using the same rasterizer as the user gallery. */
const fs=require('fs'),path=require('path'),vm=require('vm');
const {pathToFileURL}=require('url');
const root=path.resolve(__dirname,'../../../..');
const {chromium}=require(path.join(root,'tools/browser-check/node_modules/playwright-core'));
const crypto=require('crypto');
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const foodOnly=process.argv.includes('--food-only');
const eggOnly=process.argv.includes('--egg-only');
const limited=foodOnly||eggOnly;
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const report={startedAt:new Date().toISOString(),previewDataSha256:hash(path.join(__dirname,'previews/current/data.js')),exportsSha256:hash(path.join(__dirname,'reports/current/exports.json')),viewerSha256:hash(path.join(__dirname,'previews/current/viewer.js')),checks:[],errors:[]};
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1050},deviceScaleFactor:1});
  page.on('pageerror',e=>report.errors.push(e.message));
  await page.goto(pathToFileURL(path.join(__dirname,'previews/current/index.html')).href);
  await page.waitForFunction(()=>window.propsPreview?.ready());
  await page.screenshot({path:path.join(__dirname,'previews/current/gallery.png'),fullPage:true});
  const assets=await page.evaluate(()=>propsPreview.assets());
  for(const category of ['block','item','attachment']){
   await page.locator(`[data-filter="${category}"]`).click();
   const count=await page.locator('.card').count();if(count!==assets.filter(x=>x.category===category).length)throw Error('Category mismatch');
   await page.screenshot({path:path.join(__dirname,`previews/current/${category}-sheet.png`),fullPage:true});
   report.checks.push({category,count});
  }
  for(const a of assets.filter(a=>eggOnly?a.id.startsWith('house_egg'):!foodOnly||(a.id.startsWith('food_bowl')||a.id==='field_notebook'))){
   await page.evaluate(id=>propsPreview.open(id),a.id);
   for(const angle of ['oblique','front','side','back']){
    await page.locator(`[data-view="${angle}"]`).click();
    await page.locator('#view').screenshot({path:path.join(__dirname,`previews/current/${a.id}-${angle}.png`)});
   }
   await page.locator('.close').click();
  }
  await page.locator('[data-filter="all"]').click();
  await page.locator('#search').fill('橙汁');if(await page.locator('.card').count()!==1)throw Error('Search mismatch');
  await page.locator('#search').fill('');
  await page.locator('[data-id="food_bowl"]').click();
  await page.locator('#states button').filter({hasText:'垃圾',exact:true}).click();
  if((await page.evaluate(()=>propsPreview.state())).selected!=='food_bowl_garbage')throw Error('State switch mismatch');
  const box=await page.locator('#view').boundingBox();
  await page.mouse.move(box.x+200,box.y+200);await page.mouse.down();await page.mouse.move(box.x+250,box.y+220,{steps:4});await page.mouse.up();await page.mouse.wheel(0,-100);
  await page.waitForTimeout(150);
  const state=await page.evaluate(()=>propsPreview.state());if(state.az===.5||state.zoom===1)throw Error('Orbit/zoom failed');
  await page.locator('#reset').click();if((await page.evaluate(()=>propsPreview.state())).zoom!==1)throw Error('Reset failed');
  await page.keyboard.press('Escape');if(await page.locator('#detail').evaluate(e=>e.open))throw Error('Close failed');
  report.checks.push((eggOnly?'two egg states':foodOnly?'food bowl and vanilla book assets':assets.length+' assets')+', four real geometry views each','search','state switch','drag orbit','wheel zoom','reset','Escape closes');
  const equal=await page.evaluate(()=>{const ids=['food_bowl_regular','food_bowl_budget','food_bowl_premium'];const values=ids.map(id=>ASSETS.find(a=>a.id===id));return values.every(a=>JSON.stringify(a.model.vertices)===JSON.stringify(values[0].model.vertices)&&a.texture===values[0].texture);});
  if(!equal)throw Error('Tier grain geometry/texture diverged');report.checks.push('regular/budget/premium bowl vertices and pixels identical');
  for(const id of (limited?[]:['yukkuri_waste','burial_mound','reimu_ribbon','marisa_hat'])){
   await page.evaluate(id=>propsPreview.open(id),id);
   for(const scale of [.24,.6,1,1.4]){
    await page.evaluate(s=>propsPreview.setParameter('ownerScale',s),scale);
    await page.locator('#view').screenshot({path:path.join(__dirname,`previews/current/${id}-scale-${scale}.png`)});
   }
   const check=await page.evaluate(id=>{const a=propsPreview.geometry(id,{ownerScale:1}),b=propsPreview.geometry(id,{ownerScale:.24});return a.every((v,i)=>Math.abs(b[i]-v*(i%8<3?.24:1))<1e-5);},id);
   if(!check)throw Error('Owner scaling mismatch '+id);await page.locator('.close').click();
  }
  for(const id of (eggOnly?[]:foodOnly?['food_bowl_regular','food_bowl_forage','food_bowl_wheat','food_bowl_garbage']:['leaked_filling','food_bowl_regular','food_bowl_forage','food_bowl_wheat','food_bowl_garbage'])){
   await page.evaluate(id=>propsPreview.open(id),id);
   const amounts=id==='leaked_filling'?[0,1,4,12]:[0,1,4,8];const lengths=[];
   for(const q of amounts){await page.evaluate(q=>propsPreview.setParameter('quantity',q),q);lengths.push(await page.evaluate(({id,q})=>propsPreview.geometry(id,{quantity:q}).length,{id,q}));await page.locator('#view').screenshot({path:path.join(__dirname,`previews/current/${id}-quantity-${q}.png`)});}
   if(!lengths.every((n,i)=>i===0||n>lengths[i-1])||(id==='leaked_filling'&&lengths[0]!==0))throw Error('Quantity mismatch '+id);
   await page.locator('.close').click();
  }
  for(const species of (limited?[]:['reimu','marisa'])){
   await page.evaluate(id=>propsPreview.open(id),'birth_family_'+species);
   for(const count of [1,3,5]){
    await page.evaluate(n=>propsPreview.setParameter('broodCount',n),count);
    for(const angle of ['front','oblique','side','back']){await page.locator(`[data-view="${angle}"]`).click();await page.locator('#view').screenshot({path:path.join(__dirname,`previews/current/family-${species}-${count}-${angle}.png`)});}
   }
   await page.locator('.close').click();
  }
  report.checks.push(...(eggOnly?['egg full and cracked state geometry']:foodOnly?['cut grass, laid wheat and kitchen waste 0/1/4/8 units']:['owner scale .24/.6/1/1.4','filling 0/1/4/12 units','forage, wheat and garbage 0/1/4/8 units','both species compact brood 1/3/5, four views']));
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(__dirname,'previews/current/mobile.png'),fullPage:true});
  if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('Mobile overflow');
  report.checks.push('390px mobile layout without horizontal overflow');
  report.ok=!report.errors.length;
 } finally {
  fs.writeFileSync(path.join(__dirname,'reports/current/browser.json'),JSON.stringify(report,null,2));await browser.close();
 }
 if(report.errors.length)throw Error(report.errors.join('\n'));
 console.log(JSON.stringify(report));
})();
