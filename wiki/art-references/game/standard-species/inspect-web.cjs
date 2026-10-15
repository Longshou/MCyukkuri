const {chromium}=require('../../tools/browser-check/node_modules/playwright-core');
const fs=require('fs');
(async()=>{const b=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});const p=await b.newPage({viewport:{width:1200,height:1000}});
const refs=[['chen','http://blog.livedoor.jp/yukkuriplace_anko/archives/43838612.html','44038834'],['alice','http://blog.livedoor.jp/yukkuri_anko/archives/29925822.html','d67730ef'],['patchouli','http://blog.livedoor.jp/yukkuri_anko/archives/32004604.html','5960f723']];let result=[];
for(const [id,url,key] of refs){try{await p.goto(url,{waitUntil:'domcontentloaded',timeout:45000});const title=await p.title();const links=await p.locator('a').evaluateAll(as=>as.map(a=>a.href));const img=links.find(a=>a.includes(key));result.push({id,url,title,image:img});if(img){await p.goto(img,{waitUntil:'load',timeout:45000});await p.locator('img').screenshot({path:__dirname+'/'+id+'-comic.png'});}console.log(result.at(-1));}catch(e){console.log(id,e.message)}}
fs.writeFileSync(__dirname+'/web-sources.json',JSON.stringify(result,null,2));await b.close()})();
