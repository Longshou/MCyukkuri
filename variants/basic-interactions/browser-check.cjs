const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('../../tools/browser-check/node_modules/playwright-core');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const page=await browser.newPage({viewport:{width:1360,height:980}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('file:///'+path.join(__dirname,'index.html').replaceAll('\\','/'));
 await page.waitForFunction(()=>window.interactionPreview?.ready());
 for(const id of ['rub','nuzzle','lick','accessory','talk','hold']){
  await page.locator(`[data-action="${id}"]`).click();
  await page.evaluate(id=>interactionPreview.seek(['lick','accessory'].includes(id)?3.61:3.2),id);
  await page.screenshot({path:path.join(__dirname,id+'.png'),fullPage:true});
 }
 // Inspect recovery, surface contact and geometry at preparation/mid/extreme/end for both species.
 const metrics=[];
 for(const species of ['marisa','reimu']){
  await page.selectOption('#species',species);
  for(const id of ['rub','nuzzle','lick','accessory','talk','hold']){
   await page.locator(`[data-action="${id}"]`).click();
   metrics.push(await page.evaluate(id=>{
    const p=interactionPreview,checks=[];
    for(const t of [0,.8,1.5,3.61,6.31,Interactions.actions[id].duration]){
     p.seek(Math.min(t,Interactions.actions[id].duration));const s=p.snapshot();
     for(const model of p.models){if(!model.root.visible)continue;for(const mesh of Object.values(model.parts))if(!mesh.geometry.attributes.position.array.every(Number.isFinite))throw Error('Non-finite geometry');if(new THREE.Box3().setFromObject(model.parts.body).min.y<-.001)throw Error('Body below ground');}
     if(id==='accessory'&&t===3.61){const m=p.models.find(m=>m.name===s.species),box=new THREE.Box3().setFromObject(m.parts.hat);if(box.min.y<-.01)throw Error('Accessory below ground');checks.push({hatBottom:box.min.y});}
     if(id==='lick'&&(t===3.61||t===6.31)){
      const ordered=s.species==='marisa'?p.models:[p.models[1],p.models[0]],m=ordered[s.active],receiver=ordered[1-s.active];
      const tip=m.tongueRoot.localToWorld(new THREE.Vector3(0,0,-2.48));const target=receiver.root.localToWorld(new THREE.Vector3(s.active===0?-3.7:3.7,2.05,-4.63));const gap=tip.distanceTo(target);if(gap>.08)throw Error('Tongue misses receiver: '+gap);checks.push({tongueGap:gap});
     }
    }
    return {id,species:p.snapshot().species,checks};
   },id));
  }
 }
 await page.selectOption('#species','reimu');await page.locator('[data-action="accessory"]').click();await page.evaluate(()=>interactionPreview.seek(3.61));await page.screenshot({path:path.join(__dirname,'reimu-accessory.png'),fullPage:true});
 await page.locator('[data-action="nuzzle"]').click();await page.evaluate(()=>interactionPreview.seek(3.2));
 await page.screenshot({path:path.join(__dirname,'reimu-nuzzle.png'),fullPage:true});
 const before=await page.evaluate(()=>interactionPreview.snapshot().camera);
 await page.locator('[data-view="side"]').click();await page.locator('[data-action="rub"]').click();
 assert.equal(await page.evaluate(()=>interactionPreview.snapshot().camera.az),Math.PI/2);
 await page.evaluate(()=>interactionPreview.seek(2));const frozen=await page.evaluate(()=>interactionPreview.snapshot().time);await page.waitForTimeout(100);assert.equal(await page.evaluate(()=>interactionPreview.snapshot().time),frozen);
 await page.locator('#stop').click();await page.waitForTimeout(50);assert.equal(await page.evaluate(()=>interactionPreview.snapshot().actors[0].lean),0);
 await page.locator('[data-action="nuzzle"]').click();await page.evaluate(()=>{interactionPreview.seek(3.2);interactionPreview.setCamera(0,.2);});
 await page.setViewportSize({width:390,height:844});await page.waitForTimeout(50);await page.screenshot({path:path.join(__dirname,'mobile.png'),fullPage:true});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 assert.deepEqual(errors,[]);fs.writeFileSync(path.join(__dirname,'browser-report.json'),JSON.stringify({passed:true,errors,captured:9,metrics,controls:['camera retained on switch','pause','restore idle','mobile without horizontal overflow']},null,2));
 await browser.close();console.log('Rendered all six actions and mobile preview');
})().catch(e=>{console.error(e);process.exit(1);});
