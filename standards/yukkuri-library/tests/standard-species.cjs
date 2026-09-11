/* Verify actual exported models and the shared interactive renderer. */
const fs=require('fs'),path=require('path'),assert=require('assert/strict'),crypto=require('crypto');
const {pathToFileURL}=require('url');
const R=path.resolve(__dirname,'..'),project=path.resolve(R,'../..');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(project,'tools/browser-check/node_modules/playwright-core'));
const read=p=>JSON.parse(fs.readFileSync(path.join(R,p),'utf8'));
const names=['reimu','chen','alice','patchouli'];const baseline=read('assembled/reimu/reimu.bbmodel');
const report={characters:names,errors:[],sharedGeometry:true,sharedMotionTracks:true,samples:[],camera:{}};
const shared=['body','rump','face','puff','anatomy'];
const commonNames=new Set(shared.flatMap(id=>read('components/common/'+id+'.json').elements.map(e=>e.name)));
const canonical=e=>{const {uuid,...rest}=e;return rest};
for(const name of names){
 const bb=read(`assembled/${name}/${name}.bbmodel`),rig=read(`assembled/${name}/animation-rig.json`);
 const geom=read(`assembled/${name}/${name}.geo.json`)['minecraft:geometry'][0];
 const animations=read(`assembled/${name}/${name}.animation.json`).animations;
 assert.equal(new Set(bb.elements.map(e=>e.name)).size,bb.elements.length);
 const normalize=e=>{e=canonical(e);if(e.name.startsWith('face_'))e.faces.north.uv=[0,0,0,0];return e};
 for(const e of baseline.elements.filter(e=>commonNames.has(e.name)))assert.deepEqual(normalize(structuredClone(bb.elements.find(x=>x.name===e.name))),normalize(structuredClone(e)),`${name}: shared geometry ${e.name}`);
 const base=read('assembled/reimu/animation-rig.json');
 for(const [id,c] of Object.entries(base.clips))for(const [bn,track] of Object.entries(c.tracks))assert.deepEqual(rig.clips[id].tracks[bn],track,`${name}: shared motion ${id}/${bn}`);
 assert.equal(Object.keys(animations).length,bb.animations.length);
 for(const bone of geom.bones)for(const cube of bone.cubes){assert.ok(cube.size.every(v=>Number.isFinite(v)&&v>=0));if(cube.rotation)assert.ok(cube.pivot);}
}
(async()=>{
 const b=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const p=await b.newPage({viewport:{width:1660,height:950},deviceScaleFactor:1});
 p.on('pageerror',e=>report.errors.push(e.message));
 await p.goto(pathToFileURL(path.join(R,'species.html')).href);
 const frames=names.map(n=>p.frames().find(f=>f.url().includes('/'+n+'/index.html')));
 for(const f of frames)await f.waitForFunction(()=>window.actionsDebug?.ready());
 await p.waitForTimeout(250);
 const out=path.join(R,'previews/current/standard-species');fs.mkdirSync(out,{recursive:true});
 const qa=path.join(R,'reports/current/standard-species-checks');fs.mkdirSync(qa,{recursive:true});
 for(const [label,angle] of [['front','0'],['quarter','-.55'],['side','1.570796'],['rear','3.141593']]){
   await p.locator(`[data-angle="${angle}"]`).click();await p.waitForTimeout(220);
   await p.locator('.grid').screenshot({path:path.join(out,label+'.png')});
 }
 for(const [clip,times] of [['stretch',[0,.7,1.4,2.8]],['puff',[0,1.3,2.6,6.4]],['flee',[0,.3,1.5,5]]]){
   await p.selectOption('#motion',clip);
   await p.locator('[data-angle="-.55"]').click();
   for(const t of times){
     await p.evaluate(t=>window.speciesDebug.setTime(t),t);await p.waitForTimeout(180);
     for(const f of frames){const state=await f.evaluate(()=>window.actionsDebug.state());assert.equal(state.clip,clip);assert.equal(state.time,t);}
     await p.locator('.grid').screenshot({path:path.join(qa,clip+'-'+t+'.png')});
     report.samples.push({clip,time:t});
   }
 }
 // Remove accessories with the actual body-only switch and verify pixel equality.
 await p.selectOption('#motion','idle');await p.selectOption('#expression','neutral');await p.locator('[data-angle="0"]').click();await p.check('#body');await p.waitForTimeout(200);
 const raw=await Promise.all(frames.map(f=>f.evaluate(()=>({width:canvas.width,height:canvas.height,pixels:Array.from(ctx.getImageData(0,0,canvas.width,canvas.height).data)}))));
 for(const item of raw.slice(1))assert.deepEqual(item,raw[0],'all four bodies render identically at the same camera');
 report.identicalBodyPixels=true;report.bodyPixelHash=crypto.createHash('sha256').update(Buffer.from(raw[0].pixels)).digest('hex');
 await p.uncheck('#body');
 // Drag and wheel in one child must reach the other three through the parent.
 const box=await frames[1].locator('canvas').boundingBox();
 await p.mouse.move(box.x+box.width/2,box.y+box.height/2);await p.mouse.down();await p.mouse.move(box.x+box.width/2+70,box.y+box.height/2+15,{steps:5});await p.mouse.up();await p.mouse.wheel(0,-120);await p.waitForTimeout(250);
 const states=await Promise.all(frames.map(f=>f.evaluate(()=>window.actionsDebug.state())));
 for(const state of states.slice(1))for(const k of ['az','el','span'])assert.equal(state[k],states[0][k]);
 assert.notEqual(states[0].az,0);assert.ok(states[0].span<11.8);report.camera={dragSynced:true,wheelSynced:true};
 // Check standalone controls and a retained expression in each new character.
 for(const name of names.slice(1)){
   await p.goto(pathToFileURL(path.join(R,`assembled/${name}/index.html`)).href);
   await p.waitForFunction(()=>window.actionsDebug?.ready());
   await p.locator('#play').click();await p.selectOption('#expression','happy');
   await p.locator('#hand-left').click();
   assert.ok((await p.locator('#hand-left').innerText()).includes('侧发'));
   await p.waitForTimeout(150);await p.locator('canvas').screenshot({path:path.join(qa,name+'-happy-raised-lock.png')});
 }
 assert.deepEqual(report.errors,[]);
 fs.writeFileSync(path.join(R,'reports/current/standard-species-verification.json'),JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify(report,null,2));await b.close();
})().catch(e=>{console.error(e);process.exit(1)});
