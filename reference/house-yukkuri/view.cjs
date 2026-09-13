const fs=require('fs'),{chromium}=require('../../tools/browser-check/node_modules/playwright-core');
(async()=>{const b=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{const p=await b.newPage({viewport:{width:1250,height:1600}});const links=JSON.parse(fs.readFileSync(__dirname+'/images.json','utf8'));for(const i of [5,6,7]){await p.goto(links[i]);await p.locator('img').evaluate(im=>{im.style.maxWidth='1150px';im.style.maxHeight='1500px';});await p.locator('img').screenshot({path:__dirname+'/page-'+(i+1)+'.png'});console.log(i+1,links[i]);}}finally{await b.close();}})();


