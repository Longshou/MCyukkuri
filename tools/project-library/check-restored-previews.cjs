// Smoke-check restored assets without executing any generators or rewriting their data.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const {chromium}=require('../browser-check/node_modules/playwright-core');
const restored=path.resolve(process.argv[2]||'archives/art-preservation/restore-check-20260911');
const examples=[
 ['marisa-poop','preview.html','poop'],['marisa-puff','preview.html','expression'],
 ['marisa-exercise','preview.html','waddle'],['reimu-exercise','preview.html','waddle'],
 ['marisa-feeding','preview.html','feeding'],['marisa-puff-encounter','index.html','encounter'],
 ['marisa-rump-size-trials','index.html','trials'],
 ['marisa-body-length-trials','index.html','trials']
];
(async()=>{const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--allow-file-access-from-files','--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});const results=[];
try{for(const [id,file,api] of examples){
 const page=await browser.newPage({viewport:{width:1100,height:920}}),errors=[],failed=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('requestfailed',r=>failed.push(r.url()));
 await page.goto(pathToFileURL(path.join(restored,'variants',id,file)).href);
 await page.waitForFunction(api=>{
  if(api==='poop')return window.poopDebug?.state().ready;
  if(api==='expression')return window.expressionDebug?.state().ready;
  if(api==='waddle')return !!window.waddleDebug&&document.getElementById('error')?.style.display==='none';
  if(api==='feeding')return !!window.feedingPreview;
  if(api==='encounter')return window.encounterDebug?.ready();
  return window.trialsDebug?.ready();
 },api);
 await page.waitForTimeout(120);
 const dimensions=await page.locator('canvas').first().evaluate(c=>[c.width,c.height]);assert(dimensions.every(n=>n>0));
 assert.deepEqual(errors,[],id);assert.deepEqual(failed,[],id);
 results.push({id,loaded:true,errors,failedRequests:failed});await page.close();console.log(`Restored preview OK: ${id}`);
}
fs.writeFileSync(path.join(__dirname,'../../art/library/restore-verification.json'),JSON.stringify({restoredDirectory:path.relative(path.join(__dirname,'../..'),restored).replaceAll('\\','/'),fileRestore:JSON.parse(fs.readFileSync(path.join(restored,'RESTORE-REPORT.json'),'utf8')),previews:results},null,2));
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exit(1)});
