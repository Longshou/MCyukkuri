// Browser inspection exercises the exported viewer and its real pose renderer.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_CORE_PATH || path.resolve(__dirname,'../../../tools/browser-check/node_modules/playwright-core'));
const root=__dirname;
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--allow-file-access-from-files']});
 const results={errors:[],failedRequests:[],views:[],animation:{}};
 try{
  const page=await browser.newPage({viewport:{width:1380,height:970},deviceScaleFactor:1});
  page.on('pageerror',e=>results.errors.push(e.message));page.on('requestfailed',r=>results.failedRequests.push(r.url()));
  await page.goto(pathToFileURL(path.join(root,'index.html')).href);
  await page.waitForFunction(()=>window.variantsDebug?.ready());
  await page.screenshot({path:path.join(root,'comparison.png'),fullPage:true});
  for(const kind of ['tsumuri','aquatic','kotatsu']){
   for(const [label,az,el] of [['front',0,.1],['quarter',-.65,.3],['side',Math.PI/2,.12],['back',Math.PI,.15],['top',-.5,1.1]]){
    await page.evaluate(s=>{variantsDebug.set(s);variantsDebug.draw();},{kind,az,el,span:18.5,clip:'still',time:0,playing:false});
    await page.locator('canvas').screenshot({path:path.join(root,kind,`preview-${label}.png`)});
    results.views.push(kind+'/'+label);
   }
   results.animation[kind]=await page.evaluate(kind=>{
    const stats=[];
    for(const clip of ['still','idle','move'])for(const time of [0,1,2,3,4]){
     const v=variantsDebug.pose(kind,time,clip);if(!v.every(Number.isFinite))throw Error('Nonfinite pose '+kind+clip+time);
     const a=variantsDebug.pose(kind,0,clip);let difference=0;for(let i=0;i<v.length;i+=8)difference=Math.max(difference,Math.abs(v[i]-a[i]),Math.abs(v[i+1]-a[i+1]),Math.abs(v[i+2]-a[i+2]));
     stats.push({clip,time,maxDisplacement:difference});
    }
    return stats;
   },kind);
   for(const frame of [0,1,2,3,4]){
    await page.evaluate(s=>{variantsDebug.set(s);variantsDebug.draw();},{kind,az:-.55,el:.24,span:18.5,clip:'move',time:frame,playing:false});
    await page.locator('canvas').screenshot({path:path.join(root,kind,`motion-${frame}.png`)});
   }
  }
  // Inspect the inclined open mouth and the full-size retreat, including recovery.
  for(const [name,time] of [['out',0],['middle',1.1],['inside',2.5],['return',4.3],['recovered',6]]){
   for(const [view,az,el] of [['quarter',-.65,.3],['side',Math.PI/2,.12]]){
    await page.evaluate(s=>{variantsDebug.set(s);variantsDebug.draw();},{kind:'tsumuri',az,el,span:18.5,clip:'retract',time,playing:false});
    await page.locator('canvas').screenshot({path:path.join(root,'tsumuri',`retract-${name}-${view}.png`)});
   }
  }
  const shellSource=JSON.parse(fs.readFileSync(path.join(root,'tsumuri/accessory-components.json'),'utf8')).elements[0];
  results.retraction=await page.evaluate(frame=>{
   const v=variantsDebug.pose('tsumuri',2.5,'retract'),m=variantsDebug.models.tsumuri;
   const [ax,ay,az]=frame.rotation.map((n,i)=>n*(i<2?-1:1)*Math.PI/180),cx=Math.cos(ax),sx=Math.sin(ax),cy=Math.cos(ay),sy=Math.sin(ay),cz=Math.cos(az),sz=Math.sin(az);
   const rotation=[[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx],[sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx],[-sy,cy*sx,cy*cx]];
   const body=[],shell=[];
   for(let cube=0;cube<m.rig.groups.length;cube++){
    const lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];
    for(let i=cube*288;i<(cube+1)*288;i+=8){const offset=[0,1,2].map(j=>v[i+j]-frame.origin[j]),p=[0,1,2].map(j=>rotation.reduce((sum,row,k)=>sum+row[j]*offset[k],0));for(let j=0;j<3;j++){lo[j]=Math.min(lo[j],p[j]);hi[j]=Math.max(hi[j],p[j]);}}
    (m.rig.groups[cube]==='shell'?shell:body).push({cube,lo,hi});
   }
   const collision=[];for(const a of body)for(const b of shell)if(a.lo.every((x,i)=>Math.min(a.hi[i],b.hi[i])-Math.max(x,b.lo[i])>.025))collision.push([a.cube,b.cube]);
   const bounds=[0,1,2].map(i=>[Math.min(...body.map(a=>a.lo[i])),Math.max(...body.map(a=>a.hi[i]))]);
   const start=variantsDebug.pose('tsumuri',0,'retract'),end=variantsDebug.pose('tsumuri',6,'retract');
   const scales=m.rig.clips.retract.tracks.body_motion.scale;
   return {bodyBoundsInShellFrame:bounds,candidateIntersections:collision,loopClosed:start.every((x,i)=>Math.abs(x-end[i])<1e-8),neutralScale:scales['0'],withdrawnScale:scales['1.8'],returnedScale:scales['6']};
  },shellSource);
  assert(results.retraction.loopClosed);
  assert.deepEqual(results.retraction.neutralScale,[1,1,1]);
  assert.deepEqual(results.retraction.returnedScale,[1,1,1]);
  assert.equal(results.retraction.withdrawnScale[0],1,'Withdrawal keeps the ordinary body width.');
  assert.deepEqual(results.retraction.candidateIntersections,[],'Withdrawn body must fit without penetrating the shell.');
  assert(results.retraction.bodyBoundsInShellFrame[1][0]>0,'Whole body must pass the open mouth plane.');
  // Control checks: independent braids, camera remains usable while paused/playing,
  // shape selection does not reset the orbit, alternate atlases decode.
  await page.click('[data-kind="aquatic"]');await page.click('[data-clip="move"]');
  await page.waitForTimeout(200);assert.equal(await page.evaluate(()=>variantsDebug.state().playing),true);
  await page.click('#play');assert.equal(await page.evaluate(()=>variantsDebug.state().playing),false);
  await page.click('#left-hand');assert.equal(await page.evaluate(()=>variantsDebug.state().hands.left),true);
  const bounds=await page.locator('canvas').boundingBox();
  await page.mouse.move(bounds.x+400,bounds.y+250);await page.mouse.down();await page.mouse.move(bounds.x+490,bounds.y+270);await page.mouse.up();
  const camera=await page.evaluate(()=>variantsDebug.state().az);await page.click('[data-kind="kotatsu"]');assert.equal(await page.evaluate(()=>variantsDebug.state().az),camera);
  for(const e of ['happy','sleepy','sleep','neutral'])await page.selectOption('#expression',e);
  assert(await page.evaluate(()=>{const c=document.querySelector('canvas');return c.getContext('2d').getImageData(0,0,c.width,c.height).data.some((v,i)=>i%4===3&&v>0);}));
  await page.setViewportSize({width:430,height:930});await page.click('[data-kind="all"]');
  await page.evaluate(()=>{variantsDebug.set({az:-.48,el:.22,clip:'still',time:0});variantsDebug.draw();});
  await page.screenshot({path:path.join(root,'mobile-preview.png'),fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  assert.deepEqual(results.errors,[]);assert.deepEqual(results.failedRequests,[]);
  results.controls='Passed playback, pause, drag, preserved camera, independent left braid, 4 expressions, mobile layout.';
  fs.writeFileSync(path.join(root,'browser-verification.json'),JSON.stringify(results,null,2));
  console.log('Verified',results.views.length,'views, 45 animation samples, controls and mobile layout.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
