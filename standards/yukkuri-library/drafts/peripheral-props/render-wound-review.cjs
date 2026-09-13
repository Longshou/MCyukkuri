const fs=require('fs'),path=require('path'),crypto=require('crypto'),{pathToFileURL}=require('url');
const root=path.resolve(__dirname,'../../../..'),{chromium}=require(path.join(root,'tools/browser-check/node_modules/playwright-core'));
const out=path.join(__dirname,'previews/current/wound-placement');fs.mkdirSync(out,{recursive:true});
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname,f))).digest('hex');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'}),report={date:new Date().toISOString(),sourceSha256:hash('wound-surface.js'),editorSha256:hash('wound-editor.js'),dataSha256:hash('previews/current/data.js'),checks:[],errors:[]};
try{
 const page=await browser.newPage({viewport:{width:1480,height:1120},deviceScaleFactor:1});page.on('pageerror',e=>report.errors.push(e.message));
 await page.goto(pathToFileURL(path.join(__dirname,'previews/current/index.html')).href,{timeout:120000});await page.waitForFunction(()=>propsPreview.ready(),{},{timeout:120000});
 await page.locator('[data-filter="body"]').click();await page.screenshot({path:path.join(out,'gallery.png'),fullPage:true});
 await page.locator('[data-id="body_wounds"]').click();await page.waitForSelector('#w-side');
 for(const species of ['reimu','marisa'])for(const side of ['front','right','back','left','top','bottom']){
  await page.evaluate(({species,side})=>{const r=WoundSurface.defaults();r.species=species;r.showCover=false;r.wounds[0]={...r.wounds[0],side,u:side==='front'?-.5:0,v:['top','bottom'].includes(side)?0:-.7,quantity:3};woundEditor.set(r);},{species,side});
  await page.selectOption('#w-side',side);
  const angle={front:'front',right:'side',back:'back',left:'side',top:'top',bottom:'bottom'}[side];
  if(side!=='left')await page.locator(`[data-view="${angle}"]`).click();
  await page.locator('#view').screenshot({path:path.join(out,`${species}-${side}-skin.png`)});
  await page.selectOption('#w-cover','covered');await page.locator('#view').screenshot({path:path.join(out,`${species}-${side}-covered.png`)});
  const a=await page.evaluate(()=>woundEditor.scene().anchors);if(a.length!==1||a[0].side!==side)throw Error('Attachment lost '+side);
  console.log('Rendered '+species+' '+side);
 }
 await page.evaluate(()=>{const r=WoundSurface.defaults();r.wounds=[{...r.wounds[0],id:'front',quantity:3},{...r.wounds[0],id:'right',kind:'dent',side:'right',u:0,v:-.7,severity:3},{...r.wounds[0],id:'back',kind:'scratch',side:'back',u:.3,v:-.7}];woundEditor.set(r);});
 await page.locator('[data-view="oblique"]').click();await page.locator('#view').screenshot({path:path.join(out,'multiple-oblique.png')});
 await page.locator('#w-cover').selectOption('skin');
 for(const action of ['side_bend','stretch_shape','press','waddle'])for(const phase of [0,.5,1]){
  await page.evaluate(({action,phase})=>{const r=woundEditor.read();r.action=action;r.phase=phase;woundEditor.set(r);},{action,phase});
  await page.locator('#view').screenshot({path:path.join(out,`motion-${action}-${phase}.png`)});
 }
 for(const deform of ['compression','desiccation']){
  await page.evaluate(deform=>{const r=woundEditor.read();r.action='none';r.deform=deform;r.amount=3;woundEditor.set(r);},deform);
  await page.locator('#view').screenshot({path:path.join(out,`${deform}-multiple.png`)});
 }
 await page.evaluate(()=>woundEditor.set(WoundSurface.defaults()));await page.locator('[data-view="front"]').click();
 const target=await page.evaluate(()=>{const r=woundEditor.read();r.wounds[0].u=.55;r.wounds[0].v=-.58;const p=WoundSurface.scene(BODY_EFFECTS.surface_input,r).anchors[0].position,{az,el,zoom}=propsPreview.state(),f=woundEditor.frame,c=document.getElementById('view'),rect=c.getBoundingClientRect(),right=[Math.cos(az),0,Math.sin(az)],up=[-Math.sin(az)*Math.sin(el),Math.cos(el),Math.cos(az)*Math.sin(el)],v=[p[0],p[1]-f.centerY,p[2]],scale=c.height/(2*f.span*zoom);return {x:rect.left+(c.width/2+v.reduce((s,x,i)=>s+x*right[i],0)*scale)*rect.width/c.width,y:rect.top+(c.height/2-v.reduce((s,x,i)=>s+x*up[i],0)*scale)*rect.height/c.height};});
 await page.locator('#w-pick').click();await page.mouse.click(target.x,target.y);const placed=await page.evaluate(()=>woundEditor.read().wounds[0]);if(Math.abs(placed.u-.55)>.04||Math.abs(placed.v+.58)>.04)throw Error('Actual canvas pick missed');
 await page.locator('#w-add').click();await page.locator('#w-kind').selectOption('scratch');const two=await page.evaluate(()=>woundEditor.read());if(two.wounds.length!==2||two.wounds[0].u!==placed.u||two.wounds[1].kind!=='scratch')throw Error('Independent edit lost earlier wound');
 await page.locator('#w-remove').click();if((await page.evaluate(()=>woundEditor.read())).wounds.length!==1)throw Error('Remove wound failed');
 await page.evaluate(()=>{const r=woundEditor.read();r.action='side_bend';r.phase=.4;r.wounds[0].quantity=4;woundEditor.set(r);});
 const expectation=await page.evaluate(()=>{const s=woundEditor.scene();return {recipe:woundEditor.read(),values:JSON.stringify(s.vertices.filter((_,i)=>i%8<5))};});
 const downloadEvent=page.waitForEvent('download');await page.locator('#download').click();const download=await downloadEvent,file=path.join(root,'.local/wound-download-check.bbmodel');await download.saveAs(file);const bb=JSON.parse(fs.readFileSync(file,'utf8')),flat=[];
 for(const element of bb.elements)for(const face of Object.values(element.faces))for(const key of face.vertices)flat.push(...element.vertices[key],...face.uv[key].map(v=>v/512));
 if(JSON.stringify(bb.meta.wound_recipe)!==JSON.stringify(expectation.recipe)||JSON.stringify(flat)!==expectation.values)throw Error('Downloaded model differs from preview');
 const recipeFile=path.join(root,'.local/wound-import-check.json');fs.writeFileSync(recipeFile,JSON.stringify(expectation.recipe));await page.locator('#w-clear').click();if((await page.evaluate(()=>woundEditor.scene())).anchors.length)throw Error('Clear left a wound');await page.locator('#w-load-recipe').setInputFiles(recipeFile);await page.waitForFunction(()=>woundEditor.read().wounds.length===1);
 await page.locator('summary').click();await page.locator('#w-play-pose').click();await page.waitForFunction(()=>woundEditor.read().phase>=1,{},{timeout:15000});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'mobile-editor.png'),fullPage:true});if(await page.evaluate(()=>document.getElementById('detail').scrollWidth>document.getElementById('detail').clientWidth+1))throw Error('Mobile editor overflows horizontally');
 await page.keyboard.press('Escape');await page.evaluate(()=>propsPreview.open('body_tear'));await page.locator('#w-open-editor').click();await page.waitForSelector('#w-side');
 await page.setViewportSize({width:1480,height:1120});
 await page.evaluate(()=>{const r=WoundSurface.defaults();r.showCover=false;r.wounds[0].quantity=4;r.wounds.push({...r.wounds[0],id:'second',side:'right',u:0,v:-.6});woundEditor.set(r);});await page.locator('[data-view="oblique"]').click();
 for(const mode of [0,1,2,3]){await page.evaluate(mode=>{const r=woundEditor.read();r.wounds.forEach(w=>w.mode=mode);woundEditor.set(r);},mode);await page.locator('#view').screenshot({path:path.join(out,`two-outlets-mode-${mode}.png`)});}
 await page.evaluate(()=>{const r=woundEditor.read();r.wounds.forEach(w=>w.mode=2);woundEditor.set(r);});await page.locator('#w-play-flight').click();await page.waitForFunction(()=>woundEditor.read().flight>=1,{},{timeout:15000});await page.locator('#view').screenshot({path:path.join(out,'two-outlets-landed.png')});
 report.checks.push('two actors, six locations, skin and occluded views','three simultaneous independent wounds','four motion clips at start/mid/end','compressed and dry surface attachment','actual canvas click placement','add/edit/remove/clear and recipe reload','current posed model download exactly matches preview vertices and UV','motion playback completion','mobile editor layout','existing injury card opens placement editor','two independent outlets in four modes and flight playback');
 report.ok=!report.errors.length;
}finally{fs.writeFileSync(path.join(__dirname,'reports/current/wound-browser.json'),JSON.stringify(report,null,2));await browser.close();}
if(!report.ok)throw Error(JSON.stringify(report));console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exitCode=1;});
