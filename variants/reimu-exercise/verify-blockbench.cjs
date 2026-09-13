// Validate against the actual Blockbench evaluator, not a second copy of our math.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('../../tools/browser-check/node_modules/playwright-core'),{poseVertices}=require('./motion.js');
const data=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);
const clips=Object.keys(data.rig.clips).map(name=>({name,samples:Array.from({length:31},(_,i)=>{
 const clip=data.rig.clips[name],track=clip.tracks.features_lift||Object.values(clip.tracks)[0];
 const times=[...new Set(Object.values(track).flatMap(x=>Object.keys(x).map(Number)))].sort((a,b)=>a-b);
 const time=times[Math.round(i*(times.length-1)/30)],v=poseVertices(data.vertices,data.rig,time,true,false,name);
 return {time,bounds:Array.from({length:data.cubes},(_,c)=>{const min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];for(let k=c*288;k<(c+1)*288;k+=8)for(let a=0;a<3;a++){min[a]=Math.min(min[a],v[k+a]);max[a]=Math.max(max[a],v[k+a])}return {min,max}})};
})}));
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try{
 const p=await b.newPage({viewport:{width:1250,height:960}});
 await p.goto('https://web.blockbench.net/',{waitUntil:'domcontentloaded',timeout:45000});
 await p.waitForFunction(()=>typeof Codecs!=='undefined'&&!!Codecs.project,{timeout:30000});
 const model=JSON.parse(fs.readFileSync(path.join(__dirname,'reimu.bbmodel'),'utf8'));
 const exports=JSON.parse(fs.readFileSync(path.join(__dirname,'reimu.animation.json'),'utf8')).animations;
 const report=await p.evaluate(({model,clips,exports})=>{
  Codecs.project.load(model,{path:'reimu.bbmodel'});Modes.options.animate.select();const results=[];
  for(const clip of clips){
   const a=Animation.all.find(a=>a.name==='animation.reimu.'+clip.name);a.select();let error=0,example=null;
   for(const s of clip.samples){Timeline.setTime(s.time);Animator.preview();Project.model_3d.updateMatrixWorld(true);
    Cube.all.forEach((c,i)=>{const box=new THREE.Box3().setFromObject(c.mesh);for(let k=0;k<3;k++){
     const e=Math.max(Math.abs(box.min.toArray()[k]-s.bounds[i].min[k]),Math.abs(box.max.toArray()[k]-s.bounds[i].max[k]));
     if(e>error){error=e;example={element:c.name,time:s.time,axis:k,actual:[box.min.toArray(),box.max.toArray()],expected:s.bounds[i]};}
    }});
   }
   const out=a.compileBedrockAnimation(),expected=exports[a.name];let keyError=0,missing=[];
   for(const [bone,channels] of Object.entries(expected.bones))for(const [ch,keys] of Object.entries(channels)){
    const raw=out.bones[bone]?.[ch];
    if((typeof raw==='number'||typeof raw==='string')&&Number.isFinite(Number(raw))){for(const v of Object.values(keys))for(let k=0;k<3;k++)keyError=Math.max(keyError,Math.abs(v[k]-Number(raw)));continue;}
    // Blockbench writes a constant one-key channel as a vector, not a time map.
    if(Array.isArray(raw)&&raw.length===3){for(const v of Object.values(keys))for(let k=0;k<3;k++)keyError=Math.max(keyError,Math.abs(v[k]-raw[k]));continue;}
    const entries=Object.entries(raw||{}).map(([t,v])=>[Number(t),v]);
    for(const [t,v] of Object.entries(keys)){
     const entry=entries.find(([time])=>Math.abs(time-Number(t))<.000051);
     if(!entry){missing.push([bone,ch,t,raw]);continue}
     for(let k=0;k<3;k++)keyError=Math.max(keyError,Math.abs(v[k]-entry[1][k]));
    }
   }
   results.push({name:clip.name,samples:clip.samples.length,maxPoseError:error,maxKeyError:keyError,missing:missing.slice(0,5),example});
  }
  Animation.all.find(a=>a.name==='animation.reimu.exercise_sides').select();Timeline.setTime(.6);Animator.preview();
  return {cubes:Cube.all.length,bones:Group.all.length,results};
 },{model,clips,exports});
 await p.screenshot({path:path.join(__dirname,'blockbench-exercise.png')});
 for(const result of report.results){assert(result.maxPoseError<.0003,JSON.stringify(result));assert(result.maxKeyError<.0001,JSON.stringify(result));assert.equal(result.missing.length,0,JSON.stringify(result));}
 report.status='passed';fs.writeFileSync(path.join(__dirname,'blockbench-verification.json'),JSON.stringify(report,null,2));
 console.log(JSON.stringify(report));
}finally{await b.close()}})().catch(e=>{console.error(e);process.exit(1)});
