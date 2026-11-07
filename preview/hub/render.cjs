/* Command-line adapter. Bone composition and rasterization stay in their shared modules. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm');
const {RuntimePose}=require('../review/pose.js');
const job=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const raster={};
vm.runInNewContext(fs.readFileSync(path.join(job.root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const assets=path.join(job.root,'src/main/resources/assets/mcyukkuri');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const revisions=read(path.join(assets,'yukkuri_presentation/motion_corrections.json')).models;
const fits=read(path.join(assets,'yukkuri_presentation/placement_fit.json')).models;
const faces=read(path.join(assets,'yukkuri_presentation/face_library.json'));
const actors=job.actors.map(a=>{
 const model=read(a.path);
 if(!a.prop)model.expression_patches={...model.expression_patches,...faces.models[a.id].expression_patches};
 const pose=a.prop?null:new RuntimePose(model,a.scale,revisions[a.id],fits[a.id]);
 if(pose&&!pose.clips[a.clip])throw Error('Unknown clip '+a.id+'/'+a.clip);
 if(pose&&job.times.some(t=>t>pose.clips[a.clip].duration))throw Error('Beat time exceeds clip duration: '+a.id+'/'+a.clip);
 return {...a,model,pose};
});
const frames=job.times.map(t=>{
 const vertices=[];
 for(const a of actors){
  let phase=0;
  if(a.pose){
   let face=a.face||a.pose.expressionFor(a.clip,t);
   const animation=faces.animations[face];
   if(animation){phase=Math.floor(t*20/animation.frame_ticks)%2;face=animation.frames[phase];}
   if(!faces.models[a.id].selectable.includes(face))throw Error('Unknown face id: '+face);
   a.pose.expression(face);
  }
  const raw=a.pose?a.pose.frame(a.clip,t):a.model.vertices;
  const parts=a.pose?[{start:0,count:raw.length/8,slot:a.slots[a.face?phase:0]}]:a.model.parts.map((p,i)=>({...p,slot:a.slots[i]}));
  for(const p of parts)for(let i=p.start;i<p.start+p.count;i++){
   const k=i*8,v=Array.from(raw.slice(k,k+8));
   for(let c=0;c<3;c++)v[c]=(a.prop?v[c]*a.scale:v[c])+a.offset[c];
   const slot=job.slots[p.slot];
   v[3]=(slot.x+(v[3]-slot.low[0])*slot.size)/job.textureSize;
   v[4]=(slot.y+(v[4]-slot.low[1])*slot.size)/job.textureSize;
   vertices.push(...v);
  }
 }
 return vertices;
});
// Lock one camera to the union of all samples, so motion is visible between frames.
let min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];
for(const v of frames)for(let i=0;i<v.length;i+=8)for(let c=0;c<3;c++){
 min[c]=Math.min(min[c],v[i+c]);max[c]=Math.max(max[c],v[i+c]);
}
const camera=job.camera||{center:min.map((v,i)=>(v+max[i])/2),radius:Math.hypot(...max.map((v,i)=>(v-min[i])/2))};
const {center,radius}=camera;
fs.writeFileSync(path.join(job.work,'camera.json'),JSON.stringify(camera));
const texture=fs.readFileSync(job.texture);
for(let i=0;i<frames.length;i++){
 const v=frames[i];for(let k=0;k<v.length;k+=8)for(let c=0;c<3;c++)v[k+c]-=center[c];
 const pixels=raster.renderModel(v,texture,job.width,job.height,job.az,job.el,
  radius*1.1*Math.max(1,job.height/job.width),{centerY:0,textureSize:job.textureSize});
 fs.writeFileSync(path.join(job.work,i+'.rgba'),Buffer.from(pixels));
}
