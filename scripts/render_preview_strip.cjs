/* Command-line adapter. Bone composition and rasterization stay in their shared modules. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm');
const {RuntimePose}=require('../preview/review/pose.js');
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
 if(pose&&job.times.some(t=>t>pose.clips[a.clip].duration))throw Error('Beat time exceeds clip duration: '+a.id+'/'+a.clip);
 return {...a,model,pose};
});
const frames=job.times.map((t,frame)=>{
 const vertices=[],translucent=[];
 for(const a of actors){
  // Each actor stands beside its own copy of the walls: it turns by this frame's yaw and, with yield, gives way as in the game.
  if(a.pose&&job.wall)a.pose.wall={yaw:job.wall.yaws[frame],faces:job.wall.faces,yield:job.wall.yield};
  if(a.pose)a.pose.paperCover=a.paperSlots?(living[a.id]?.accessory_prefixes||[]):null;
  let phase=0;
  let selectedFace;
  if(a.pose){
   let face=a.face||a.pose.expressionFor(a.clip,t);
   
   const animation=a.draftFace?null:faces?.animations[face];
   if(animation){let tick=Math.floor(t*20)%animation.frame_ticks.reduce((a,b)=>a+b,0);while(tick>=animation.frame_ticks[phase])tick-=animation.frame_ticks[phase++];face=animation.frames[phase];}
   if(faces&&!a.draftFace&&!faces.models[a.id].selectable.includes(face))throw Error('Unknown face id: '+face);
   if(a.face&&!faces)throw Error('Checkout has no face library: '+job.root);
   a.pose.expression(face);selectedFace=face;
  }
  const raw=a.pose?a.pose.frame(a.clip,t):a.model.vertices;
  const parts=a.pose?[{start:0,count:raw.length/8,slot:a.faceSlots?a.faceSlots[selectedFace]:a.slots[a.face?phase:0]}]:a.model.parts.map((p,i)=>({...p,slot:a.slots[i]}));
  for(const p of parts){
   const out=p.translucent?translucent:vertices;
   for(let i=p.start;i<p.start+p.count;i++){
    const k=i*8,v=Array.from(raw.slice(k,k+8));
    for(let c=0;c<3;c++)v[c]=(a.prop?v[c]*a.scale:v[c])+a.offset[c];
    const slot=job.slots[p.slot];
    v[3]=(slot.x+(v[3]-slot.low[0])*slot.size)/job.textureSize;
    v[4]=(slot.y+(v[4]-slot.low[1])*slot.size)/job.textureSize;
    out.push(...v);
   }
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
 return {vertices,translucent};
});
// Lock one camera to the union of all samples, so motion is visible between frames.
let min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];
for(const f of frames)for(const v of [f.vertices,f.translucent])for(let i=0;i<v.length;i+=8)for(let c=0;c<3;c++){
 min[c]=Math.min(min[c],v[i+c]);max[c]=Math.max(max[c],v[i+c]);
}
const camera=job.camera||{center:min.map((v,i)=>(v+max[i])/2),radius:Math.hypot(...max.map((v,i)=>(v-min[i])/2))};
const {center,radius}=camera;
fs.writeFileSync(path.join(job.work,'camera.json'),JSON.stringify(camera));
const texture=fs.readFileSync(job.texture);
function blendTranslucent(pixels,opaqueVerts,transVerts,width,height,az,el,span,textureSize){
 const {right,up,eye}=raster.orthographicBasis(az,el),scale=height/(2*span);
 const depth=new Float64Array(width*height);depth.fill(-Infinity);
 const fillDepth=verts=>{
  for(let i=0;i<verts.length;i+=24){
   let nx=verts[i+5],ny=verts[i+6],nz=verts[i+7];
   if(nx!==verts[i+13]||ny!==verts[i+14]||nz!==verts[i+15]||nx!==verts[i+21]||ny!==verts[i+22]||nz!==verts[i+23]){
    const a=[0,1,2].map(k=>verts[i+8+k]-verts[i+k]),b=[0,1,2].map(k=>verts[i+16+k]-verts[i+k]);
    nx=a[1]*b[2]-a[2]*b[1];ny=a[2]*b[0]-a[0]*b[2];nz=a[0]*b[1]-a[1]*b[0];
   }
   if(nx*eye[0]+ny*eye[1]+nz*eye[2]<=0)continue;
   const tri=[];
   for(let j=0;j<3;j++){const k=i+j*8,x=verts[k],y=verts[k+1],z=verts[k+2];
    tri.push([width/2+(x*right[0]+z*right[2])*scale,height/2-(x*up[0]+y*up[1]+z*up[2])*scale,x*eye[0]+y*eye[1]+z*eye[2],verts[k+3]*textureSize,verts[k+4]*textureSize]);}
   const [a,b,d]=tri,det=(b[1]-d[1])*(a[0]-d[0])+(d[0]-b[0])*(a[1]-d[1]);if(Math.abs(det)<1e-8)continue;
   const x0=Math.max(0,Math.floor(Math.min(a[0],b[0],d[0]))),x1=Math.min(width-1,Math.ceil(Math.max(a[0],b[0],d[0])));
   const y0=Math.max(0,Math.floor(Math.min(a[1],b[1],d[1]))),y1=Math.min(height-1,Math.ceil(Math.max(a[1],b[1],d[1])));
   for(let y=y0;y<=y1;y++)for(let x=x0;x<=x1;x++){
    const wa=((b[1]-d[1])*(x+.5-d[0])+(d[0]-b[0])*(y+.5-d[1]))/det,wb=((d[1]-a[1])*(x+.5-d[0])+(a[0]-d[0])*(y+.5-d[1]))/det,wc=1-wa-wb;
    if(wa<-1e-7||wb<-1e-7||wc<-1e-7)continue;
    const z=wa*a[2]+wb*b[2]+wc*d[2],idx=y*width+x;if(z<=depth[idx]+1e-7)continue;
    if(verts[i+3]>=0){const u=Math.max(0,Math.min(textureSize-1,Math.floor(wa*a[3]+wb*b[3]+wc*d[3]))),v=Math.max(0,Math.min(textureSize-1,Math.floor(wa*a[4]+wb*b[4]+wc*d[4])));
     if(texture[(v*textureSize+u)*4+3]<128)continue;}
    depth[idx]=z;
   }
  }
 };
 fillDepth(opaqueVerts);
 const tris=[];
 for(let i=0;i<transVerts.length;i+=24){
  const cz=((transVerts[i]+transVerts[i+8]+transVerts[i+16])/3)*eye[0]+((transVerts[i+1]+transVerts[i+9]+transVerts[i+17])/3)*eye[1]+((transVerts[i+2]+transVerts[i+10]+transVerts[i+18])/3)*eye[2];
  tris.push([cz,i]);
 }
 tris.sort((a,b)=>a[0]-b[0]);
 for(const [,i] of tris){
  const light=.78+.22*Math.max(0,transVerts[i+5]*-.35+transVerts[i+6]*.8+transVerts[i+7]*-.48);
  const tri=[];
  for(let j=0;j<3;j++){const k=i+j*8,x=transVerts[k],y=transVerts[k+1],z=transVerts[k+2];
   tri.push([width/2+(x*right[0]+z*right[2])*scale,height/2-(x*up[0]+y*up[1]+z*up[2])*scale,x*eye[0]+y*eye[1]+z*eye[2],transVerts[k+3]*textureSize,transVerts[k+4]*textureSize]);}
  const [a,b,d]=tri,det=(b[1]-d[1])*(a[0]-d[0])+(d[0]-b[0])*(a[1]-d[1]);if(Math.abs(det)<1e-8)continue;
  const x0=Math.max(0,Math.floor(Math.min(a[0],b[0],d[0]))),x1=Math.min(width-1,Math.ceil(Math.max(a[0],b[0],d[0])));
  const y0=Math.max(0,Math.floor(Math.min(a[1],b[1],d[1]))),y1=Math.min(height-1,Math.ceil(Math.max(a[1],b[1],d[1])));
  for(let y=y0;y<=y1;y++)for(let x=x0;x<=x1;x++){
   const wa=((b[1]-d[1])*(x+.5-d[0])+(d[0]-b[0])*(y+.5-d[1]))/det,wb=((d[1]-a[1])*(x+.5-d[0])+(a[0]-d[0])*(y+.5-d[1]))/det,wc=1-wa-wb;
   if(wa<-1e-7||wb<-1e-7||wc<-1e-7)continue;
   const z=wa*a[2]+wb*b[2]+wc*d[2],idx=y*width+x;if(z<=depth[idx]+1e-7)continue;
   const u=Math.max(0,Math.min(textureSize-1,Math.floor(wa*a[3]+wb*b[3]+wc*d[3]))),v=Math.max(0,Math.min(textureSize-1,Math.floor(wa*a[4]+wb*b[4]+wc*d[4])));
   const t=(v*textureSize+u)*4,sa=texture[t+3]/255;if(sa<=0.01)continue;
   const p=idx*4,da=pixels[p+3]/255,outA=sa+da*(1-sa);
   for(let c=0;c<3;c++)pixels[p+c]=Math.round((texture[t+c]*light*sa+pixels[p+c]*da*(1-sa))/Math.max(outA,1e-6));
   pixels[p+3]=Math.round(outA*255);
  }
 }
}
for(let i=0;i<frames.length;i++){
 const v=frames[i].vertices,tv=frames[i].translucent;
 for(const arr of [v,tv])for(let k=0;k<arr.length;k+=8)for(let c=0;c<3;c++)arr[k+c]-=center[c];
 const span=radius*1.1*Math.max(1,job.height/job.width);
 const pixels=raster.renderModel(v,texture,job.width,job.height,job.az,job.el,
  span,{centerY:0,textureSize:job.textureSize,colors:[[176,172,164],[150,146,140],[120,116,112]]});
 if(tv.length)blendTranslucent(pixels,v,tv,job.width,job.height,job.az,job.el,span,job.textureSize);
 fs.writeFileSync(path.join(job.work,i+'.rgba'),Buffer.from(pixels));
}
