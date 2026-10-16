/* Offline staging of current exported meshes. No browser or native UI access. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const raster={};
vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const [scenePath,out]=process.argv.slice(2),scene=JSON.parse(fs.readFileSync(scenePath,'utf8'));
const asset=path.join(root,'src/main/resources/assets/mcyukkuri');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const corrections=read(path.join(asset,'yukkuri_presentation/motion_corrections.json')).models;
const house=read(path.join(asset,'yukkuri_presentation/house_ecology.json')).models;
const fits=read(path.join(asset,'yukkuri_presentation/placement_fit.json')).models;
const texture=fs.readFileSync(path.join(out,'atlas.rgba'));
for(const shot of scene.shots){
 const vertices=[];
 const grips=new Map();
 for(const instance of shot.instances){
  const info=scene.models[instance.id];
  const model=read(path.join(root,info.model));
  let source=model.vertices;
  if(info.character){
   const extra={...corrections[instance.id],clips:{...corrections[instance.id].clips,...house[instance.id].clips}};
   const pose=new RuntimePose(model,1,extra,fits[instance.id]||null);
   pose.expression(instance.face||'neutral');
   source=pose.frame(instance.clip||'idle',instance.time||0);
   if(instance.key&&model.grip){
    const points=model.grip.points.map((p,i)=>{const m=pose.world[model.grip.bones[i]];
     return [0,1,2].map(k=>m[k*4]*p[0]+m[k*4+1]*p[1]+m[k*4+2]*p[2]+m[k*4+3]);});
    const p=[0,1,2].map(k=>(points[0][k]+points[1][k])/2),a=instance.angle||0,c=Math.cos(a),s=Math.sin(a),at=instance.at;
    grips.set(instance.key,[p[0]*c-p[2]*s+at[0],p[1]+at[1]-.25,p[0]*s+p[2]*c+at[2]]);
   }
  }
  const tex=scene.textures[info.character?instance.id+'/'+(instance.face||'neutral'):instance.id];
  const sc=instance.scale||[1,1,1],a=instance.angle||0,c=Math.cos(a),s=Math.sin(a),at=instance.socketOf?grips.get(instance.socketOf):instance.at||[0,0,0];
  const omitted=new Set(instance.omit||[]);
  const allowed=info.character?null:model.parts.filter(p=>!omitted.has(p.name));
  const list=allowed||[{start:0,count:source.length/8}];
  for(const part of list)for(let i=part.start*8;i<(part.start+part.count)*8;i+=8){
   const x=source[i]*sc[0],y=source[i+1]*sc[1],z=source[i+2]*sc[2];
   const nx=source[i+5]/sc[0],ny=source[i+6]/sc[1],nz=source[i+7]/sc[2],nl=Math.hypot(nx,ny,nz);
   vertices.push(x*c-z*s+at[0],y+at[1],x*s+z*c+at[2],
    (tex.x+source[i+3]*tex.size)/scene.atlasSize,(tex.y+source[i+4]*tex.size)/scene.atlasSize,
    (nx*c-nz*s)/nl,ny/nl,(nx*s+nz*c)/nl);
  }
 }
 const pixels=raster.renderModel(vertices,texture,scene.width,scene.height,shot.az??.20,shot.el??.62,shot.span??8.8,
  {centerY:shot.centerY??2.0,textureSize:scene.atlasSize});
 fs.writeFileSync(path.join(out,shot.id+'.rgba'),Buffer.from(pixels));
}
console.log('Rendered '+scene.shots.length+' house ecology views offline.');
