'use strict';
const fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const {buildHeatClips}=require('./heat-clips.js');
const models=['alice','marisa','reimu','chen','patchouli'];
for(const id of models){
 const file=path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri/'+id+'.json');
 const model=JSON.parse(fs.readFileSync(file,'utf8'));
 const pose=new RuntimePose(model,1,null,null);
 const clips=buildHeatClips(model,{variant:id==='alice'?'alice_rapem':'generic'});
 Object.assign(pose.clips,clips);
 let frames=0,min=Infinity,max=-Infinity,bad=0;
 for(const [name,clip] of Object.entries(clips)){
  for(let i=0;i<=10;i++){
   const t=clip.duration*i/10;
   const out=pose.frame(name,t);
   for(let k=0;k<out.length;k+=8){
    const v=out[k+1]; if(!Number.isFinite(v))bad++;
    if(Number.isFinite(v)){min=Math.min(min,v);max=Math.max(max,v);}
   }
   frames++;
  }
 }
 console.log(id,'clips',Object.keys(clips).join(','),'frames',frames,'y',min.toFixed(2),max.toFixed(2),'bad',bad);
}
