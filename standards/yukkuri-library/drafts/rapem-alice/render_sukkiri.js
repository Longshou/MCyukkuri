'use strict';
// Offline two-body frames of the ordinary pair sukkiri with the game's own chain: server rear stand and body yaw, the shared
// contact clock, the root cue (lift, Rz*Ry*Rx*S), growth scale, the running clip, the layered anatomy (mamu turned to the rear)
// and the synced face. CPU raster only; no browser, no game.
//   node render_sukkiri.js <job.json>
// job: {out, width, height, frames:[{file, camera:{az,el,span,centerY}, actors:[actor]}]}
// actor: {id, g, x, z, yaw, clip, t, face, cue:[pitch,yaw,roll,sx,sy,sz]|null, lean, anatomy:{part,amount}|null}
const fs=require('fs'),path=require('path'),vm=require('vm');
const {decode,encode}=require('./png.js');
const ROOT=path.resolve(__dirname,'../../../..'),ASSET=path.join(ROOT,'src/main/resources/assets/mcyukkuri');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^﻿/,''));
const {RuntimePose}=require(path.join(ROOT,'preview/review/pose.js'));
const raster={};vm.runInNewContext(fs.readFileSync(path.join(ROOT,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const revisions=read(path.join(ASSET,'yukkuri_presentation/motion_corrections.json')).models;
const fits=read(path.join(ASSET,'yukkuri_presentation/placement_fit.json')).models;
const library=read(path.join(ASSET,'yukkuri_presentation/face_library.json'));
const MAMU_REAR=read(path.join(ASSET,'yukkuri_presentation/adult_states.json')).mamu_rear_offset;
const poses={},textures={},rest={};
function pose(id){
 if(poses[id])return poses[id];
 const p=new RuntimePose(read(path.join(ASSET,'yukkuri',id+'.json')),1,revisions[id],fits[id]);
 p.expression('neutral');rest[id]=Float64Array.from(p.frame('idle',0));
 return poses[id]=p;
}
function faceFile(id,face){
 const aliases=library.aliases,local=library.models[id].face_aliases||{};
 if(aliases[face])face=aliases[face];if(local[face])face=local[face];
 if(!library.models[id].selectable.includes(face))throw Error('Unknown face '+id+'/'+face);
 const animation=library.animations[face];
 return {face,file:path.join(ASSET,'textures/entity',id,(animation?animation.frames[0]:face)+'.png')};
}
function texture(file){return textures[file]||(textures[file]=decode(file));}
const TILE=256,GRID=4,ATLAS=TILE*GRID;
function atlasCopy(atlas,tex,slot){
 const ox=slot%GRID*TILE,oy=Math.floor(slot/GRID)*TILE;
 for(let y=0;y<TILE;y++)for(let x=0;x<TILE;x++){
  const s=(Math.floor(y*tex.h/TILE)*tex.w+Math.floor(x*tex.w/TILE))*4,d=((oy+y)*ATLAS+ox+x)*4;
  for(let k=0;k<4;k++)atlas[d+k]=tex.data[s+k];
 }
}
// Rz(roll)*Ry(yaw)*Rx(pitch)*S, as Overlays.presentation.
function cueMatrix(c){
 const r=Math.PI/180,[p,y,z]=[c[0]*r,c[1]*r,c[2]*r];
 const cx=Math.cos(p),sx=Math.sin(p),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
 const Rx=[[1,0,0],[0,cx,-sx],[0,sx,cx]],Ry=[[cy,0,sy],[0,1,0],[-sy,0,cy]],Rz=[[cz,-sz,0],[sz,cz,0],[0,0,1]];
 const mul=(a,b)=>a.map((row,i)=>[0,1,2].map(j=>row[0]*b[0][j]+row[1]*b[1][j]+row[2]*b[2][j]));
 const R=mul(mul(Rz,Ry),Rx);
 return R.map(row=>[row[0]*c[3],row[1]*c[4],row[2]*c[5]]);
}
const apply=(m,v)=>[m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2],m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2],m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]];
function bodyParts(model){return model.parts.filter(p=>!p.anatomy);}
// YukkuriModel.presentationLift: the lowest rest vertex of the body (no anatomy or puff) above the floor, in model units.
function cueLift(id,m){
 const model=poses[id].model,v=rest[id];let min=Infinity;
 for(const part of bodyParts(model))if(!part.name.includes('puff'))
  for(let k=part.start*8;k<(part.start+part.count)*8;k+=8)min=Math.min(min,apply(m,[v[k],v[k+1],v[k+2]])[1]);
 return Math.max(0,.096-min);
}
function actorVertices(a,slot){
 const p=pose(a.id),model=p.model;
 const {file}=faceFile(a.id,a.face);
 p.expression(faceFile(a.id,a.face).face);
 const body=Float64Array.from(p.frame(a.clip,a.t)),rootBody=p.world[p.rootBone].slice();
 const out=[];
 const push=(v,k,mirror)=>{
  let x=v[k],y=v[k+1],z=v[k+2],nx=v[k+5],ny=v[k+6],nz=v[k+7];
  if(mirror){x=-x;z=-z+MAMU_REAR;nx=-nx;nz=-nz;}
  out.push(x,y,z,v[k+3],v[k+4],nx,ny,nz);
 };
 for(const part of bodyParts(model))for(let k=part.start*8;k<(part.start+part.count)*8;k+=8)push(body,k,false);
 if(a.anatomy&&a.anatomy.amount>0){
  // The anatomy bones take the extend clip at duration*amount over the running clip (YukkuriModel); carry the anatomy pass
  // through the running clip's root so it stays on the moving body.
  const clip=p.clips['anatomy_'+a.anatomy.part+'_extend'];
  const layer=Float64Array.from(p.frame('anatomy_'+a.anatomy.part+'_extend',clip.duration*a.anatomy.amount)),rootLayer=p.world[p.rootBone];
  const inv=invert(rootLayer),toBody=mul4(rootBody,inv);
  for(const part of model.parts)if(part.anatomy===a.anatomy.part)
   for(let k=part.start*8;k<(part.start+part.count)*8;k+=8){
    const q=xf(toBody,[layer[k],layer[k+1],layer[k+2]],1),n=xf(toBody,[layer[k+5],layer[k+6],layer[k+7]],0);
    const v=[q[0],q[1],q[2],layer[k+3],layer[k+4],n[0],n[1],n[2]];push(v,0,a.anatomy.part==='mamu');
   }
 }
 const m=cueMatrix(a.cue||[0,0,0,1,1,1]),lift=a.cue?cueLift(a.id,m):0;
 const ang=(a.yaw||0)*Math.PI/180,c=Math.cos(ang),s=Math.sin(ang),g=a.g||1;
 for(let k=0;k<out.length;k+=8){
  let q=apply(m,[out[k],out[k+1],out[k+2]]);
  q=[q[0]*g,(q[1]+lift)*g,q[2]*g-(a.lean||0)];
  let n=apply(m,[out[k+5],out[k+6],out[k+7]]);const l=Math.hypot(...n)||1;n=n.map(v=>v/l);
  out[k]=q[0]*c+q[2]*s+(a.x||0);out[k+1]=q[1];out[k+2]=-q[0]*s+q[2]*c+(a.z||0);
  out[k+5]=n[0]*c+n[2]*s;out[k+6]=n[1];out[k+7]=-n[0]*s+n[2]*c;
  if(out[k+3]>=0){out[k+3]=(out[k+3]+slot%GRID)/GRID;out[k+4]=(out[k+4]+Math.floor(slot/GRID))/GRID;}
 }
 return {vertices:out,file};
}
function mul4(a,b){const r=new Array(16).fill(0);for(let i=0;i<4;i++)for(let j=0;j<4;j++)for(let k=0;k<4;k++)r[i*4+j]+=a[i*4+k]*b[k*4+j];return r;}
function xf(m,v,w){return [0,1,2].map(r=>m[r*4]*v[0]+m[r*4+1]*v[1]+m[r*4+2]*v[2]+m[r*4+3]*w);}
function invert(m){
 // Rigid-with-scale 4x4 (row-major): invert the 3x3 and the translation.
 const a=[[m[0],m[1],m[2]],[m[4],m[5],m[6]],[m[8],m[9],m[10]]];
 const det=a[0][0]*(a[1][1]*a[2][2]-a[1][2]*a[2][1])-a[0][1]*(a[1][0]*a[2][2]-a[1][2]*a[2][0])+a[0][2]*(a[1][0]*a[2][1]-a[1][1]*a[2][0]);
 const inv=[[ (a[1][1]*a[2][2]-a[1][2]*a[2][1])/det,-(a[0][1]*a[2][2]-a[0][2]*a[2][1])/det,(a[0][1]*a[1][2]-a[0][2]*a[1][1])/det],
  [-(a[1][0]*a[2][2]-a[1][2]*a[2][0])/det,(a[0][0]*a[2][2]-a[0][2]*a[2][0])/det,-(a[0][0]*a[1][2]-a[0][2]*a[1][0])/det],
  [(a[1][0]*a[2][1]-a[1][1]*a[2][0])/det,-(a[0][0]*a[2][1]-a[0][1]*a[2][0])/det,(a[0][0]*a[1][1]-a[0][1]*a[1][0])/det]];
 const t=[m[3],m[7],m[11]],it=inv.map(row=>-(row[0]*t[0]+row[1]*t[1]+row[2]*t[2]));
 return [inv[0][0],inv[0][1],inv[0][2],it[0],inv[1][0],inv[1][1],inv[1][2],it[1],inv[2][0],inv[2][1],inv[2][2],it[2],0,0,0,1];
}
function render(frame,width,height){
 const atlas=Buffer.alloc(ATLAS*ATLAS*4),chunks=[];
 frame.actors.forEach((a,slot)=>{const r=actorVertices(a,slot);chunks.push(r.vertices);atlasCopy(atlas,texture(r.file),slot);});
 const total=chunks.reduce((n,c)=>n+c.length,0),vertices=new Float64Array(total);let at=0;for(const c of chunks){vertices.set(c,at);at+=c.length;}
 const cam=frame.camera,pixels=raster.renderModel(vertices,atlas,width,height,cam.az,cam.el,cam.span,{centerY:cam.centerY,textureSize:ATLAS});
 const canvas=Buffer.alloc(width*height*4),scale=height/(2*cam.span),se=Math.sin(cam.el),ce=Math.cos(cam.el);
 // Floor at y=0 seen at elevation el: the horizon line of the floor plane through the pair centre.
 const floor=height/2+cam.centerY*ce*scale;
 for(let y=0;y<height;y++)for(let x=0;x<width;x++){
  const i=(y*width+x)*4;
  let color=y>floor?[214,226,210]:[238,242,236];
  if(Math.abs(y-floor)<.8)color=[168,184,170];
  const a=pixels[i+3]/255;for(let k=0;k<3;k++)canvas[i+k]=color[k]*(1-a)+pixels[i+k]*a;canvas[i+3]=255;
 }
 return canvas;
}
if(require.main===module){
 const job=read(process.argv[2]);fs.mkdirSync(job.out,{recursive:true});
 for(const frame of job.frames)encode(job.width,job.height,render(frame,job.width,job.height),path.join(job.out,frame.file));
 console.log('rendered',job.frames.length,'frames to',job.out);
}
module.exports={render,pose};
