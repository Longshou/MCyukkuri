/* Actual depth-buffer comparison of visible facial ink with/without the body. */
const fs=require('fs'),path=require('path'),dir=process.argv[2],lib=process.argv[3];
const code=fs.readFileSync(path.join(lib,'runtime/render.js'),'utf8')+'\n'+fs.readFileSync(path.resolve(lib,'../../.local/previews/body-standard/pose.js'),'utf8');
const {posed,renderModel}=new Function(code+'\nreturn {posed,renderModel};')();
const {models,expressions}=JSON.parse(fs.readFileSync(path.join(dir,'input.json'))),results=[];
for(const[id,m]of Object.entries(models))for(const expr of expressions){
 const tex=fs.readFileSync(path.join(dir,id+'-'+expr+'.rgba'));
 for(const action of ['waddle','hop','flee'])for(const az of [0,.55])for(let phase=0;phase<=24;phase++){
  const t=m.clips[action].duration*phase/24,opts={centerY:10.5,textureSize:128};
  const draw=parts=>renderModel(posed({...m,parts},action,t),tex,320,240,az,.06,3.6,opts);
  const full=draw(m.parts),expected=draw(m.parts.filter(p=>p.name.startsWith('face_')));
  for(const name of ['face_eye_left','face_eye_right','face_mouth']){
   const face=posed({...m,parts:m.parts.filter(p=>p.name===name)},action,t);
   // Skin-coloured sides/back of the expression card are not eye or mouth ink.
   const front=face.filter((v,i)=>face[Math.floor(i/24)*24+7]<-.5);
   const solo=renderModel(front,tex,320,240,az,.06,3.6,opts);
   let total=0,hidden=0;
   for(let i=0;i<solo.length;i+=4)if(solo[i+3]){
    total++;
    if(expected[i]!==full[i]||expected[i+1]!==full[i+1]||expected[i+2]!==full[i+2])hidden++;
   }
   results.push({id,expression:expr,action,az,phase,t,name,total,hidden});
  }
 }
}
const failures=results.filter(r=>r.hidden>0);
const report={samples:results.length,hidden_pixels:results.reduce((s,r)=>s+r.hidden,0),failures,status:failures.length?'failed':'passed'};
fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({samples:report.samples,hidden_pixels:report.hidden_pixels,failures:failures.length}));
if(failures.length)process.exitCode=1;
