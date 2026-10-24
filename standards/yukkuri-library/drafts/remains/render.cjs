/* Offline remains preview: the runtime mesh, idle pose and expression patch, the
 * RemainsRenderer affine squash/tilt and the optional ground decal, drawn by the
 * library's software rasterizer.  Usage: node render.cjs <tasks.json>
 */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const tasks=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const motions=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'),'utf8')).models;
const fits=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'),'utf8')).models;
const poses=new Map();
function poseFor(id){
 if(!poses.has(id)){
  const model=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri',id+'.json'),'utf8'));
  poses.set(id,new RuntimePose(model,['reimu_deibu','reimu_wasa_deibu'].includes(id)?1.5:1,motions[id],fits[id]||null));
 }
 return poses.get(id);
}
// Model space follows YukkuriModel (1/16 block units, ground at y=0). Squash, then
// tilt about the ground contact, matching RemainsRenderer's PoseStack order.
function place(vertices,t,dx){
 const out=Float64Array.from(vertices),roll=(t.roll||0)*Math.PI/180,pitch=(t.pitch||0)*Math.PI/180;
 const cr=Math.cos(roll),sr=Math.sin(roll),cp=Math.cos(pitch),sp=Math.sin(pitch);
 for(let i=0;i<out.length;i+=8){
  for(const [o,s] of [[0,t.sx],[1,t.sy],[2,t.sz]])out[i+o]*=s;
  let x=out[i],y=out[i+1],z=out[i+2];
  [x,y]=[x*cr-y*sr,x*sr+y*cr];[y,z]=[y*cp-z*sp,y*sp+z*cp];
  out[i]=x+dx;out[i+1]=y+(t.ty||0);out[i+2]=z;
  // Normals only shade; the inverse-scale keeps lighting plausible after squash.
  let nx=out[i+5]/t.sx,ny=out[i+6]/t.sy,nz=out[i+7]/t.sz;
  [nx,ny]=[nx*cr-ny*sr,nx*sr+ny*cr];[ny,nz]=[ny*cp-nz*sp,ny*sp+nz*cp];
  const n=Math.hypot(nx,ny,nz)||1;out[i+5]=nx/n;out[i+6]=ny/n;out[i+7]=nz/n;
 }
 return out;
}
function decalQuad(radius,dx,u0,v0,u1,v1){
 // Two upward triangles just above the ground, winding toward +y.
 const y=.06,r=radius,c=[[-r,y,-r,u0,v0],[r,y,-r,u1,v0],[r,y,r,u1,v1],[-r,y,r,u0,v1]];
 const tri=[c[0],c[2],c[1],c[0],c[3],c[2]],out=[];
 for(const p of tri)out.push(p[0]+dx,p[1],p[2],p[3],p[4],0,1,0);
 return out;
}
let overlayLength=0;
// YukkuriModel.renderRemainsSkin: a cylindrical map over the rest skin (u around the
// body with the face at .5, v from top to sole), lifted a little along the normal.
// Seam triangles wrap in game (repeat); the preview leaves the back seam out.
function skinOverlay(pose,posed,names){
 const model=pose.model,src=pose.src,parts=model.parts.filter(p=>names.includes(p.name)),out=[];
 let lo=[1e9,1e9,1e9],hi=[-1e9,-1e9,-1e9];
 for(const p of parts)for(let i=p.start;i<p.start+p.count;i++)for(let k=0;k<3;k++){lo[k]=Math.min(lo[k],src[i*8+k]);hi[k]=Math.max(hi[k],src[i*8+k]);}
 const cx=(lo[0]+hi[0])/2,cz=(lo[2]+hi[2])/2,hw=Math.max(.001,(hi[0]-lo[0])/2),hd=Math.max(.001,(hi[2]-lo[2])/2),h=Math.max(.001,hi[1]-lo[1]);
 for(const p of parts)for(let f=p.start;f<p.start+p.count;f+=3){
  const uv=[];for(let j=0;j<3;j++){const i=(f+j)*8;uv.push([(Math.atan2((src[i]-cx)/hw,-(src[i+2]-cz)/hd)+Math.PI)/(2*Math.PI),Math.min(1,Math.max(0,1-(src[i+1]-lo[1])/h))]);}
  if(Math.max(...uv.map(a=>a[0]))-Math.min(...uv.map(a=>a[0]))>.5)continue;
  for(let j=0;j<3;j++){const i=(f+j)*8,n=[posed[i+5],posed[i+6],posed[i+7]];
   out.push(posed[i]+n[0]*.03,posed[i+1]+n[1]*.03,posed[i+2]+n[2]*.03,Math.min(.999,uv[j][0]),Math.min(.999,uv[j][1]),n[0],n[1],n[2]);}
 }
 overlayLength=out.length;return Float64Array.from(out);
}
function join(list){const out=new Float64Array(list.reduce((n,a)=>n+a.length,0));let o=0;for(const a of list){out.set(a,o);o+=a.length;}return out;}
for(const job of tasks.jobs){
 // One square atlas: each tile is a model texture or a decal texture.
 const tiles=job.textures,grid=Math.ceil(Math.sqrt(tiles.length)),tile=tasks.tile,size=tile*grid,atlas=Buffer.alloc(size*size*4);
 tiles.forEach((t,index)=>{const raw=fs.readFileSync(t.file),col=index%grid,row=Math.floor(index/grid);
  const tw=t.width||t.size,th=t.height||t.size;if(raw.length!==tw*th*4)throw Error('Bad texture '+t.file);
  for(let y=0;y<tile;y++)for(let x=0;x<tile;x++){const sx=Math.floor(x*tw/tile),sy=Math.floor(y*th/tile);
   raw.copy(atlas,((row*tile+y)*size+col*tile+x)*4,(sy*tw+sx)*4,(sy*tw+sx)*4+4);}});
 const uvTile=(index,u,v)=>[((index%grid)+u)/grid,(Math.floor(index/grid)+v)/grid];
 const all=[];
 for(const actor of job.actors){
  const pose=poseFor(actor.model);pose.expression(actor.expression||'neutral');
  let v=pose.frame('idle',0).slice();
  if(actor.accessory===false){
   const prefixes=actor.accessory_prefixes||[],kept=[];
   for(const part of pose.model.parts)if(!prefixes.some(p=>part.name.startsWith(p)))kept.push(v.slice(part.start*8,(part.start+part.count)*8));
   v=join(kept);
  }
  if(actor.overlay!==undefined)v=join([v,skinOverlay(pose,v,actor.skin||['rounded_body_surface'])]);
  v=place(v,actor.transform,actor.x||0);
  const overlayStart=actor.overlay!==undefined?v.length-overlayLength:v.length;
  for(let i=0;i<v.length;i+=8){const [u,w]=uvTile(i<overlayStart?actor.texture:actor.overlay,v[i+3],v[i+4]);v[i+3]=u;v[i+4]=w;}
  if(actor.decal){const [u0,v0]=uvTile(actor.decal.texture,0,0),[u1,v1]=uvTile(actor.decal.texture,1,1);all.push(Float64Array.from(decalQuad(actor.decal.radius,actor.x||0,u0,v0,u1,v1)));}
  all.push(v);
 }
 const pixels=raster.renderModel(join(all),atlas,job.width,job.height,job.az,job.el,job.span,{centerY:job.centerY,textureSize:size});
 fs.writeFileSync(job.out,Buffer.from(pixels));
}
console.log('Rendered '+tasks.jobs.length+' remains views.');
