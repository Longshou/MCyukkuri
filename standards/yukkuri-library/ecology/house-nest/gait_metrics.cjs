/* Posed-mesh extents of the house locomotion clips, used to judge stretch. Usage: node gait_metrics.cjs OUT.json [MODEL_DIR] */
'use strict';
const fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const asset=path.join(root,'src/main/resources/assets/mcyukkuri');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const corrections=read(path.join(asset,'yukkuri_presentation/motion_corrections.json')).models;
const house=read(path.join(asset,'yukkuri_presentation/house_ecology.json')).models;
const fits=read(path.join(asset,'yukkuri_presentation/placement_fit.json')).models;
const modelDir=process.argv[3]?path.resolve(process.argv[3]):path.join(asset,'yukkuri');
const result={};
for(const id of ['house_reimu','house_marisa']){
 const model=read(path.join(modelDir,id+'.json'));
 const extra={...corrections[id],clips:{...corrections[id].clips,...house[id].clips}};
 const pose=new RuntimePose(model,1,extra,fits[id]||null);
 const box=out=>{const lo=[1e9,1e9,1e9],hi=[-1e9,-1e9,-1e9];
  for(let i=0;i<out.length;i+=8){if(out[i]===0&&out[i+1]===0&&out[i+2]===0)continue;for(let k=0;k<3;k++){lo[k]=Math.min(lo[k],out[i+k]);hi[k]=Math.max(hi[k],out[i+k]);}}return {lo,hi};};
 const rest=box(pose.frame('idle',0));
 const entry={rest:{height:rest.hi[1]-rest.lo[1],length:rest.hi[2]-rest.lo[2],width:rest.hi[0]-rest.lo[0],bottom:rest.lo[1]}};
 for(const [clip,step] of [['house_crawl',.05],['waddle',.05],['hop',.01]]){
  const c=pose.clips[clip],frames=[];
  for(let t=0;t<c.duration-1e-9;t+=step){const b=box(pose.frame(clip,t));
   frames.push({t:+t.toFixed(3),height:+(b.hi[1]-b.lo[1]).toFixed(4),length:+(b.hi[2]-b.lo[2]).toFixed(4),width:+(b.hi[0]-b.lo[0]).toFixed(4),bottom:+b.lo[1].toFixed(4)});}
  const mx=k=>Math.max(...frames.map(f=>f[k])),mn=k=>Math.min(...frames.map(f=>f[k]));
  entry[clip]={heightMaxPct:+((mx('height')/entry.rest.height-1)*100).toFixed(1),heightMinPct:+((mn('height')/entry.rest.height-1)*100).toFixed(1),
   lengthMaxPct:+((mx('length')/entry.rest.length-1)*100).toFixed(1),lengthMinPct:+((mn('length')/entry.rest.length-1)*100).toFixed(1),
   widthMaxPct:+((mx('width')/entry.rest.width-1)*100).toFixed(1),bottomMin:mn('bottom'),bottomMax:mx('bottom'),frames};
 }
 result[id]=entry;
}
fs.writeFileSync(process.argv[2],JSON.stringify(result,null,1));
for(const [id,e] of Object.entries(result)){console.log(id,JSON.stringify(e.rest));for(const c of ['house_crawl','waddle','hop']){const {frames,...s}=e[c];console.log(' ',c,JSON.stringify(s));}}
