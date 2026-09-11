/* Verify real generated viewers, poses, camera controls and exported animation IDs. */
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'../../../..');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(root,'tools/browser-check/node_modules/playwright-core'));
const read=p=>JSON.parse(fs.readFileSync(path.join(__dirname,p),'utf8'));
const recipes=read('recipes.json').variants,errors=[],report={models:{},errors};
const reportDir=path.join(__dirname,'reports/current');
const checks=path.join(reportDir,'checks');fs.mkdirSync(checks,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1180,height:980}});
  page.on('pageerror',e=>errors.push(e.message));
  for(const [name,r] of Object.entries(recipes)){
   const bb=read(`assembled/${name}/${name}.bbmodel`),rig=read(`assembled/${name}/animation-rig.json`),geo=read(`assembled/${name}/${name}.geo.json`)['minecraft:geometry'][0];
   assert.equal(bb.elements.length,rig.groups.length);
   assert.equal(bb.elements.length,geo.bones.reduce((n,b)=>n+b.cubes.length,0));
   const names=new Set(rig.bones.map(b=>b.name));
   assert.equal(names.size,rig.bones.length);assert.ok(rig.groups.every(n=>names.has(n)));
   if(name==='reimu_wasa'){
    assert.ok(names.has('sideburn_left')&&names.has('sideburn_right'));
    assert.ok(!names.has('hand_left')&&!names.has('hand_right'));
    assert.ok(!bb.elements.some(e=>/^(cuff|tied_lock|lock_tip)/.test(e.name)));
   }
   assert.equal(new Set(bb.elements.map(e=>e.uuid)).size,bb.elements.length);
   for(const e of bb.elements)for(let i=0;i<3;i++)assert.ok(Number.isFinite(e.from[i])&&e.to[i]>e.from[i],e.name);
   const animations=read(`assembled/${name}/${name}.animation.json`).animations;
   // An oval DOS must be tall in the body itself, not just in its hat.
   const body=bb.elements.filter(e=>/^(soft_head_slice|left_cheek_slice|right_cheek_slice)/.test(e.name));
   const bodyWidth=Math.max(...body.map(e=>e.to[0]))-Math.min(...body.map(e=>e.from[0]));
   const bodyHeight=Math.max(...body.map(e=>e.to[1]))-Math.min(...body.map(e=>e.from[1]));
   if(name==='marisa_dos')assert.ok(bodyHeight/bodyWidth>1.2,'DOS body is tall and oval');
   if(name==='reimu_deibu')assert.ok(bodyHeight/16>.22,'Deibu retains the taller robust build');
   if(name==='reimu_deibu'){
    const low=bb.elements.find(e=>e.name==='soft_head_slice_3'),high=bb.elements.find(e=>e.name==='soft_head_slice_29');
    assert.ok(high.to[0]-high.from[0]<(low.to[0]-low.from[0])*.85,'Deibu retains a narrower upper head');
    const bottom=bb.elements.find(e=>e.name==='soft_head_slice_0');
    assert.ok(bottom.to[0]-bottom.from[0]<bodyWidth*.96,'Deibu lower corners curve inward');
   }
   if(name==='marisa_dos'){
    assert.ok(Math.abs(bodyHeight/16-1.8)<1e-6,'DOS physical body height is 1.8 m');
    const eyes=bb.elements.filter(e=>e.name==='face_eye_left'||e.name==='face_eye_right');
    assert.ok(eyes.every(e=>(e.from[1]+e.to[1])/2>bodyHeight*.70),'DOS face occupies upper head');
    const scalp=bb.elements.find(e=>e.name==='hair_cap'),rim=bb.elements.find(e=>e.name==='brim_center');
    assert.ok(rim.from[1]<=scalp.to[1]&&rim.to[1]>=scalp.from[1],'hat rim meets scalp');
   }
   for(const bone of geo.bones){
    const cubes=bb.elements.filter((e,i)=>rig.groups[i]===bone.name);
    cubes.forEach((e,i)=>{assert.deepEqual(e.from,bone.cubes[i].origin);assert.deepEqual(e.to.map((v,j)=>v-e.from[j]),bone.cubes[i].size)});
   }
   assert.equal(Object.keys(animations).length,bb.animations.length);
   for(const key of Object.keys(rig.clips))assert.ok(animations[`animation.${name}.${key}`]);
   await page.goto(pathToFileURL(path.join(__dirname,`assembled/${name}/index.html`)).href);
   await page.waitForFunction(()=>window.actionsDebug?.ready());
   assert.equal(await page.evaluate(()=>actionsDebug.state().expression),r.signature_expression||'neutral');
   await page.click('#play');
   for(const [view,angle] of [['front','0'],['side','1.5708'],['back','3.14159']]){
    await page.click(`[data-angle="${angle}"]`);await page.waitForTimeout(80);
    await page.locator('canvas').screenshot({path:path.join(checks,`${name}-${view}.png`)});
   }
   await page.click('[data-angle="0"]');
   await page.selectOption('#motion','puff');await page.selectOption('#expression','puff');
   for(const t of [0,1.2,2.7,6.4]){
    await page.evaluate(t=>actionsDebug.setTime(t),t);await page.waitForTimeout(80);
    await page.locator('canvas').screenshot({path:path.join(checks,`${name}-puff-${t}.png`)});
   }
   // Every authored pose and sampled transition must have finite positions/normals.
   const finite=await page.evaluate(()=>{
    for(const [id,clip] of Object.entries(DATA.model.rig.clips))for(const t of [0,clip.duration*.25,clip.duration*.5,clip.duration*.75,clip.duration]){
     const v=poseVertices(DATA.model.vertices,DATA.model.rig,t,true,false,id);if(!v.every(Number.isFinite))return false;
    }return true;
   });assert.ok(finite,'finite poses '+name);
   await page.selectOption('#motion','idle');await page.selectOption('#expression','neutral');
   await page.click('#hand-left');await page.click('#hand-right');
   const before=await page.evaluate(()=>actionsDebug.state().az);
   const box=await page.locator('canvas').boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+90,box.y+box.height/2+10);await page.mouse.up();
   assert.notEqual(before,await page.evaluate(()=>actionsDebug.state().az));
   await page.mouse.wheel(0,90);await page.waitForTimeout(80);
   assert.ok(await page.evaluate(()=>actionsDebug.state().span>DATA.preview.span));
   report.models[name]={cubes:bb.elements.length,animations:bb.animations.length,body_width:bodyWidth,body_height:bodyHeight,body_width_m:bodyWidth/16,body_height_m:bodyHeight/16,body_height_width_ratio:bodyHeight/bodyWidth,geometry_export_matches:true,finite_poses:finite,drag_and_zoom:true};
  }
  await page.setViewportSize({width:1440,height:1100});
  await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);await page.waitForFunction(()=>window.designReady);await page.waitForTimeout(100);
  await page.screenshot({path:path.join(checks,'overview.png'),fullPage:true});
  await page.click('[data-mode="dos"]');await page.click('#front');await page.waitForTimeout(100);
  await page.locator('#scale').screenshot({path:path.join(checks,'dos-scale.png')});
  await page.click('#motion');await page.waitForTimeout(250);assert.ok(await page.evaluate(()=>designDebug.state().playing));await page.click('#motion');
  await page.setViewportSize({width:390,height:844});await page.waitForTimeout(100);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'responsive width');
  await page.screenshot({path:path.join(checks,'mobile.png'),fullPage:true});
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(reportDir,'verification.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
