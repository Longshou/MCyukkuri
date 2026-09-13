const {chromium}=require('../../tools/browser-check/node_modules/playwright-core');const {pathToFileURL}=require('url');const path=require('path');const fs=require('fs');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});const p=await browser.newPage({viewport:{width:1140,height:850}});fs.mkdirSync(__dirname+'/refinement',{recursive:true});
for(const name of ['chen','patchouli']){await p.goto(pathToFileURL(path.resolve('standards/yukkuri-library/assembled/'+name+'/index.html')).href);await p.waitForFunction(()=>window.actionsDebug?.ready());
for(const [label,a,e] of [['front',0,.1],['quarter',-.6,.18],['side',Math.PI/2,.15],['rear-top',2.75,.65]]){
await p.evaluate(({a,e,s})=>{az=a;el=e;span=s;centerY=6.4;clip='idle';time=0;playing=false;dirty=true},{a,e,s:name==='chen'&&label==='rear-top'?14.5:11.8});await p.waitForTimeout(150);await p.locator('canvas').screenshot({path:__dirname+'/refinement/'+name+'-'+label+'.png'});
} }await browser.close()})();
