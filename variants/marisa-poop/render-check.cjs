const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {poseVertices}=require('./motion.js'),{renderModel}=require('./renderer.js');
const root=__dirname,d=JSON.parse(fs.readFileSync(path.join(root,'preview-data.json'),'utf8')),tex=fs.readFileSync(path.join(root,'texture.rgba'));
fs.mkdirSync(path.join(root,'frames'),{recursive:true});
let minCharacter=Infinity,minDeposit=Infinity,maxStep=0,previous=null,maxExitError=0;
const markIndex=d.rig.groups.indexOf('rear_mark');
for(let frame=0;frame<=640;frame++){
 const t=frame/100,v=poseVertices(d.vertices,d.rig,t,true,false,'poop');
 for(let i=0;i<v.length;i+=8){assert(v.slice(i,i+8).every(Number.isFinite));assert(Math.abs(Math.hypot(...v.slice(i+5,i+8))-1)<1e-5);if(i<d.depositStart*288)minCharacter=Math.min(minCharacter,v[i+1]);else if(t>=3.55)minDeposit=Math.min(minDeposit,v[i+1]);}
 if(previous&&t>=3.56)for(let i=d.depositStart*288;i<v.length;i+=8)maxStep=Math.max(maxStep,Math.hypot(...v.slice(i,i+3).map((a,k)=>a-previous[i+k])));
 previous=v;
 if(t>=1.6&&t<3.55){
  // The X center and the proximal filling tip should meet throughout all three pushes.
  const mark=[0,0,0],tip=[0,0,0];let n=0;
  for(let j=0;j<36;j++)for(let k=0;k<3;k++)mark[k]+=v[markIndex*288+j*8+k]/36;
  for(let c=d.depositStart;c<d.depositStart+5;c++)for(let j=0;j<36;j++){
   const ix=c*288+j*8;if(Math.abs(d.vertices[ix+2]-1.55)<1e-6){for(let k=0;k<3;k++)tip[k]+=v[ix+k];n++;}
  }
  tip.forEach((_,k)=>tip[k]/=n);maxExitError=Math.max(maxExitError,Math.hypot(...tip.map((a,k)=>a-mark[k])));
 }
}
assert(maxExitError<.09,`filling detached from X while growing: ${maxExitError}`);
assert(minCharacter>=-.001,`character floor ${minCharacter}`);assert(minDeposit>=-.001,`deposit floor ${minDeposit}`);assert(maxStep<.2,`release discontinuity ${maxStep}`);
const end=poseVertices(d.vertices,d.rig,6.4,true,false,'poop'),start=poseVertices(d.vertices,d.rig,0,true,false,'poop');
for(let i=0;i<d.depositStart*288;i++)assert(Math.abs(end[i]-start[i])<1e-6,'character did not recover');
for(const t of [0,1.4,2.8,3.6,4.25,6.4])for(const angle of [0,65,135]){
 const v=poseVertices(d.vertices,d.rig,t,true,false,'poop');
 fs.writeFileSync(path.join(root,'frames',`stage-${t}-${angle}.rgba`),renderModel(v,tex,440,460,angle*Math.PI/180,.12,13));
}
for(let i=0;i<160;i++){
 const v=poseVertices(d.vertices,d.rig,i*.04,true,false,'poop');
 for(const angle of [30,110])fs.writeFileSync(path.join(root,'frames',`${angle}-${String(i).padStart(3,'0')}.rgba`),renderModel(v,tex,380,400,angle*Math.PI/180,.12,13));
}
const report={samples:641,minCharacterY:minCharacter,minReleasedDepositY:minDeposit,maxDepositStepAt100Hz:maxStep,maxFillingExitError:maxExitError,restRecovered:true,finiteVerticesAndUnitNormals:true};
const filling=d.vertices.slice(d.depositStart*288);for(let i=0;i<filling.length;i+=8){filling[i+1]+=11.6-2.2;filling[i+2]-=3;}
for(const angle of [25,90,150])fs.writeFileSync(path.join(root,'frames',`filling-${angle}.rgba`),renderModel(filling,tex,460,380,angle*Math.PI/180,.32,2.6));
for(const [label,t,angle] of [['mouth',2.8,0],['mark',0,180]]){
 const v=poseVertices(d.vertices,d.rig,t,true,false,'poop');for(let i=0;i<v.length;i+=8)v[i+1]+=label==='mouth'?7.9:9.8;
 fs.writeFileSync(path.join(root,'frames',`detail-${label}.rgba`),renderModel(v,tex,460,380,angle*Math.PI/180,0,label==='mouth'?4:2.8));
}
fs.writeFileSync(path.join(root,'motion-verification.json'),JSON.stringify(report,null,2));console.log(report);
