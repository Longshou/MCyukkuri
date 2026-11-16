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
// The loader's later clip revisions (YukkuriModelLoader order), each only on the skeleton it was exported for.
const later=['house_ecology','toilet_contact'].map(f=>path.join(assets,'yukkuri_presentation',f+'.json')).filter(fs.existsSync).map(p=>read(p).models);
const revised=(id,model)=>{
 const names=JSON.stringify(model.bones.map(b=>b.name));let revision=revisions[id];
 for(const models of later)if(revision&&models[id]&&JSON.stringify(models[id].bone_names)===names)revision={...revision,clips:{...revision.clips,...models[id].clips}};
 return revision;
};
// A --before checkout can predate the face library; its ordinary strips still render.
const facePath=path.join(assets,'yukkuri_presentation/face_library.json');
const faces=fs.existsSync(facePath)?read(facePath):null;
const livingPath=path.join(assets,'yukkuri_presentation/living.json');
const living=fs.existsSync(livingPath)?read(livingPath).variants:{};
const actors=job.actors.map(a=>{
 const model=read(a.path);
 if(!a.prop&&faces)model.expression_patches={...model.expression_patches,...faces.models[a.id].expression_patches};
 if(!a.prop&&a.draftFace)model.expression_patches={...model.expression_patches,[a.face]:a.draftFace};
 if(a.draftClip)model.clips={...model.clips,[a.clip]:a.draftClip};
 const pose=a.prop?null:new RuntimePose(model,a.scale,revised(a.id,model),fits[a.id]);
 // Walls stand beside the first actor only: it turns by the wall's yaw and, with yield, gives way as in the game.
 if(pose&&!pose.clips[a.clip])throw Error('Unknown clip '+a.id+'/'+a.clip);
 if(pose&&!a.moment&&job.times.some(t=>t>pose.clips[a.clip].duration))throw Error('Beat time exceeds clip duration: '+a.id+'/'+a.clip);
 return {...a,model,pose};
});
const frames=job.times.map((t,frame)=>{
 const vertices=[];
 for(const a of actors){
  // Each actor stands beside its own copy of the walls: it turns by this frame's yaw and, with yield, gives way as in the game.
  if(a.pose&&job.wall)a.pose.wall={yaw:job.wall.yaws[frame],faces:job.wall.faces,yield:job.wall.yield};
  if(a.pose)a.pose.paperCover=a.paperSlots?(living[a.id]?.accessory_prefixes||[]):null;
  let phase=0,clip=a.clip,time=t,intensity=1,momentFace=null;
  if(a.moment){
   for(const step of a.moment.steps){
    const duration=a.pose.clips[step.clip].duration/step.tempo;
    if(time>=duration){time-=duration;continue;}
    clip=step.clip;intensity=step.intensity;
    momentFace='neutral';for(const [at,face] of step.faces)if(time>=at)momentFace=face;
    time*=step.tempo;break;
   }
  }
  let selectedFace;
  if(a.pose){
   let face=momentFace||a.face||a.pose.expressionFor(clip,time);
   if(faces){face=faces.aliases[face]||face;face=faces.models[a.id].face_aliases?.[face]||face;}
   const animation=a.draftFace?null:faces?.animations[face];
   if(animation){let tick=Math.floor(t*20)%animation.frame_ticks.reduce((a,b)=>a+b,0);while(tick>=animation.frame_ticks[phase])tick-=animation.frame_ticks[phase++];face=animation.frames[phase];}
   if(faces&&!a.draftFace&&!faces.models[a.id].selectable.includes(face))throw Error('Unknown face id: '+face);
   if(a.face&&!faces)throw Error('Checkout has no face library: '+job.root);
   a.pose.expression(face);selectedFace=face;
  }
  const raw=a.pose?a.pose.frame(clip,time,null,a.moment?intensity:null):a.model.vertices;
  const parts=a.pose?[{start:0,count:raw.length/8,slot:a.faceSlots?a.faceSlots[selectedFace]:a.slots[a.face?phase:0]}]:a.model.parts.map((p,i)=>({...p,slot:a.slots[i]}));
  for(const p of parts)for(let i=p.start;i<p.start+p.count;i++){
   const k=i*8,v=Array.from(raw.slice(k,k+8));
   for(let c=0;c<3;c++)v[c]=(a.prop?v[c]*a.scale:v[c])+a.offset[c];
   const slot=job.slots[p.slot];
   v[3]=(slot.x+(v[3]-slot.low[0])*slot.size)/job.textureSize;
   v[4]=(slot.y+(v[4]-slot.low[1])*slot.size)/job.textureSize;
   vertices.push(...v);
  }
  if(a.pose&&a.paperSlots){
   for(const [sheet,slotIdx] of [[a.pose.paperTop,a.paperSlots[0]],[a.pose.paperUnder,a.paperSlots[1]]])if(sheet){
    const slot=job.slots[slotIdx];
    for(let k=0;k<sheet.length;k+=8){
     const v=sheet.slice(k,k+8);
     for(let c=0;c<3;c++)v[c]+=a.offset[c];
     v[3]=(slot.x+(v[3]-slot.low[0])*slot.size)/job.textureSize;
     v[4]=(slot.y+(v[4]-slot.low[1])*slot.size)/job.textureSize;
     vertices.push(...v);
    }
   }
  }
 }
 // Each wall face as a quarter-block-thick slab: a checkered sheet of quarter-block tiles toward the body, then its top and ends.
 for(const a of actors)for(const [nx,nz,distance,low,high] of a.pose&&job.wall?job.wall.faces:[]){
  const o=a.offset,span=16,tile=4;
  const corner=(along,up,out)=>[o[0]+nx*(distance*16+out)-nz*along,o[1]+up,o[2]+nz*(distance*16+out)+nx*along];
  const quad=(q,colour)=>{
   const a=q[1].map((v,k)=>v-q[0][k]),b=q[2].map((v,k)=>v-q[0][k]);
   const n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],length=Math.hypot(...n);
   for(const index of [0,1,2,0,2,3])vertices.push(...q[index],colour,0,...n.map(v=>v/length));
  };
  for(let s=-span;s<span;s+=tile){
   for(let h=low*16;h<high*16-1e-6;h+=tile)
    quad([corner(s,h,0),corner(s+tile,h,0),corner(s+tile,h+tile,0),corner(s,h+tile,0)],-1-((Math.round(s/tile)+Math.round(h/tile))&1));
   quad([corner(s,high*16,0),corner(s+tile,high*16,0),corner(s+tile,high*16,tile),corner(s,high*16,tile)],-3);
  }
  quad([corner(-span,low*16,tile),corner(-span,low*16,0),corner(-span,high*16,0),corner(-span,high*16,tile)],-3);
  quad([corner(span,low*16,0),corner(span,low*16,tile),corner(span,high*16,tile),corner(span,high*16,0)],-3);
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
  radius*1.1*Math.max(1,job.height/job.width),{centerY:0,textureSize:job.textureSize,colors:[[176,172,164],[150,146,140],[120,116,112]]});
 fs.writeFileSync(path.join(job.work,i+'.rgba'),Buffer.from(pixels));
}
