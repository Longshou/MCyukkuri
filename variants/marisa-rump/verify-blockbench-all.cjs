const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('../../tools/browser-check/node_modules/playwright-core'),{poseVertices}=require('./motion.js');
const data=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);
const expected=Array.from({length:65},(_,f)=>{const time=f*1.2/64,v=poseVertices(data.vertices,data.rig,time);return {time,bounds:Array.from({length:data.cubes},(_,c)=>{const min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];for(let i=c*288;i<(c+1)*288;i+=8)for(let k=0;k<3;k++){min[k]=Math.min(min[k],v[i+k]);max[k]=Math.max(max[k],v[i+k]);}return {min,max};})};});
(async()=>{const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
const p=await browser.newPage({viewport:{width:1250,height:900}});await p.goto('https://web.blockbench.net/',{waitUntil:'domcontentloaded',timeout:45000});await p.waitForFunction(()=>typeof Codecs!=='undefined'&&!!Codecs.project,{timeout:30000});
const model=JSON.parse(fs.readFileSync(path.join(__dirname,'marisa.bbmodel'),'utf8'));
const report=await p.evaluate(({model,expected})=>{Codecs.project.load(model,{path:'marisa.bbmodel'});Modes.options.animate.select();Animation.all[0].select();let maxError=0;
for(const sample of expected){Timeline.setTime(sample.time);Animator.preview();Project.model_3d.updateMatrixWorld(true);Cube.all.forEach((c,i)=>{const box=new THREE.Box3().setFromObject(c.mesh);for(let k=0;k<3;k++){maxError=Math.max(maxError,Math.abs(box.min.toArray()[k]-sample.bounds[i].min[k]),Math.abs(box.max.toArray()[k]-sample.bounds[i].max[k]));}});}
Timeline.setTime(.3);Animator.preview();return {format:Format.id,cubes:Cube.all.length,groups:Group.all.length,samples:expected.length,maxError,bedrock:Animation.all[0].compileBedrockAnimation()};},{model,expected});
assert(report.maxError<.00001,`Imported pose error: ${report.maxError}`);
fs.writeFileSync(path.join(__dirname,"blockbench-export-debug.json"),JSON.stringify(report,null,2));const exported=JSON.parse(fs.readFileSync(path.join(__dirname,'marisa.animation.json'),'utf8')).animations['animation.marisa.waddle'];
for(const [bone,channels] of Object.entries(exported.bones))for(const [channel,keys] of Object.entries(channels))for(const [t,v] of Object.entries(keys)){
 const entries=Object.entries(report.bedrock.bones[bone][channel]);const match=entries.find(([k])=>Math.abs(Number(k)-Number(t))<.000051);assert(match,`Missing compiled key ${bone} ${channel} ${t}`);for(let i=0;i<3;i++)assert(Math.abs(Number(match[1][i])-v[i])<.000001,`Export value mismatch ${bone} ${t}`);
}
await p.screenshot({path:path.join(__dirname,'blockbench-import.png')});delete report.bedrock;report.status='passed';report.animationExport='all keys match official Blockbench Bedrock exporter';fs.writeFileSync(path.join(__dirname,'blockbench-verification.json'),JSON.stringify(report,null,2));console.log(report);
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});


