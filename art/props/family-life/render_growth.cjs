/* Offline stem growth preview: runtime bodies through the shared pose core (idle, chosen face), each placed by a
 * scale/roll/offset, plus prepared stalk and pedicel tubes, drawn by the library's software rasterizer.
 * Usage: node render_growth.cjs <tasks.json>
 * tasks: {tile, jobs:[{textures:[{file,width,height}], actors:[{model, expression, texture, transform:{sx,sy,sz,roll},
 *         offset:[x,y,z]}], meshes:[{vertices:[x,y,z,u,v,nx,ny,nz,...], texture}], width, height, az, el, span, centerY, shift:[x,y,z], out}]}
 */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm');
const root=path.resolve(__dirname,'../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const tasks=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const motions=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/motion_corrections.json'),'utf8')).models;
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'),'utf8')).models;
const poses=new Map(),frames=new Map();
function frame(id,expression){
 const key=id+'/'+expression;
 if(!frames.has(key)){
  if(!poses.has(id)){
   const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',id+'.json'),'utf8'));
   poses.set(id,new RuntimePose(model,1,motions[id],fits[id]||null));
  }
  const pose=poses.get(id);pose.expression(expression);frames.set(key,pose.frame('idle',0).slice());
 }
 return frames.get(key);
}
// RemainsRenderer order: squash, then roll about the ground contact; then the offset. Normals take the inverse scale.
function place(vertices,t,offset){
 const out=Float64Array.from(vertices),roll=(t.roll||0)*Math.PI/180,cr=Math.cos(roll),sr=Math.sin(roll);
 for(let i=0;i<out.length;i+=8){
  let x=out[i]*t.sx,y=out[i+1]*t.sy,z=out[i+2]*t.sz;
  [x,y]=[x*cr-y*sr,x*sr+y*cr];
  out[i]=x+offset[0];out[i+1]=y+offset[1];out[i+2]=z+offset[2];
  let nx=out[i+5]/t.sx,ny=out[i+6]/t.sy,nz=out[i+7]/t.sz;
  [nx,ny]=[nx*cr-ny*sr,nx*sr+ny*cr];
  const n=Math.hypot(nx,ny,nz)||1;out[i+5]=nx/n;out[i+6]=ny/n;out[i+7]=nz/n;
 }
 return out;
}
function join(list){const out=new Float64Array(list.reduce((n,a)=>n+a.length,0));let o=0;for(const a of list){out.set(a,o);o+=a.length;}return out;}
for(const job of tasks.jobs){
 const tiles=job.textures,grid=Math.ceil(Math.sqrt(tiles.length)),tile=tasks.tile,size=tile*grid,atlas=Buffer.alloc(size*size*4);
 tiles.forEach((t,index)=>{const raw=fs.readFileSync(t.file),col=index%grid,row=Math.floor(index/grid);
  if(raw.length!==t.width*t.height*4)throw Error('Bad texture '+t.file);
  for(let y=0;y<tile;y++)for(let x=0;x<tile;x++){const sx=Math.floor(x*t.width/tile),sy=Math.floor(y*t.height/tile);
   raw.copy(atlas,((row*tile+y)*size+col*tile+x)*4,(sy*t.width+sx)*4,(sy*t.width+sx)*4+4);}});
 const remap=(v,index)=>{for(let i=0;i<v.length;i+=8){v[i+3]=((index%grid)+Math.min(.999,Math.max(0,v[i+3])))/grid;v[i+4]=(Math.floor(index/grid)+Math.min(.999,Math.max(0,v[i+4])))/grid;}return v;};
 const all=[];
 // shift moves the whole scene so the stem, not the body origin, sits in the frame centre.
 const shift=job.shift||[0,0,0],moved=v=>{for(let i=0;i<v.length;i+=8)for(let k=0;k<3;k++)v[i+k]+=shift[k];return v;};
 for(const actor of job.actors)all.push(moved(remap(place(frame(actor.model,actor.expression||'neutral'),actor.transform,actor.offset||[0,0,0]),actor.texture)));
 for(const mesh of job.meshes||[])all.push(moved(remap(Float64Array.from(mesh.vertices),mesh.texture)));
 const pixels=raster.renderModel(join(all),atlas,job.width,job.height,job.az,job.el,job.span,{centerY:job.centerY,textureSize:size});
 fs.writeFileSync(job.out,Buffer.from(pixels));
}
console.log('Rendered '+tasks.jobs.length+' stem growth views.');
