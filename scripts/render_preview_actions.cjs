/* Offline action frames using the same RuntimePose and software rasterizer as the review page. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm');
const {RuntimePose}=require('../preview/review/pose.js');
const root=path.resolve(__dirname,'..');
function arg(name){const i=process.argv.indexOf(name);return i<0?null:process.argv[i+1];}
const taskFile=arg('--tasks');
if(!taskFile)throw Error('Usage: node scripts/render_preview_actions.cjs --tasks <tasks.json>');
const tasks=JSON.parse(fs.readFileSync(taskFile,'utf8'));
const motions=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'),'utf8')).models;
const fits=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'),'utf8')).models;
const presentations=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/living.json'),'utf8')).variants;
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const poses=new Map(),models=new Map(),textures=new Map();
function readJson(relative){return JSON.parse(fs.readFileSync(path.join(root,relative),'utf8'));}
function poseFor(id){
 if(poses.has(id))return poses.get(id);
 const relative='src/main/resources/assets/mcyukkuri/yukkuri/'+id+'.json';
 const model=readJson(relative),scale=['reimu_deibu','reimu_wasa_deibu'].includes(id)?1.5:1;
 const pose=new RuntimePose(model,scale,motions[id],fits[id]||null);poses.set(id,pose);models.set(id,model);return pose;
}
function rawTexture(key){
 if(textures.has(key))return textures.get(key);
 const info=tasks.textures[key];if(!info)throw Error('No prepared texture '+key);
 const raw=fs.readFileSync(info.file);if(raw.length!==info.width*info.height*4)throw Error('Invalid texture bytes '+key);
 const value={...info,raw};textures.set(key,value);return value;
}
function interpolate(from,to,q){return (from||[0,0,0]).map((v,i)=>v+((to||from||[0,0,0])[i]-v)*q);}
function rotateXZ(x,z,yaw){const c=Math.cos(yaw),s=Math.sin(yaw);return [x*c+z*s,-x*s+z*c];}
function inverseRotateXZ(x,z,yaw){const c=Math.cos(yaw),s=Math.sin(yaw);return [x*c-z*s,x*s+z*c];}
function actorPosition(actor,q){return interpolate(actor.from||[actor.x||0,actor.y||0,actor.z||0],actor.to,q);}
function scenePoint(actor,point,q){
 const at=actorPosition(actor,q),scale=actor.scale===undefined?1:actor.scale,yaw=(actor.yaw||0)*Math.PI/180;
 const [x,z]=rotateXZ(point[0]*scale,point[2]*scale,yaw);return [at[0]+x,at[1]+point[1]*scale,at[2]+z];
}
function transform(vertices,translation,scale,yaw,tile){
 const out=new Float64Array(vertices.length),c=Math.cos(yaw),s=Math.sin(yaw);
 for(let i=0;i<vertices.length;i+=8){
  const x=vertices[i],z=vertices[i+2],nx=vertices[i+5],nz=vertices[i+7];
  out[i]=(x*c+z*s)*scale+translation[0];out[i+1]=vertices[i+1]*scale+translation[1];out[i+2]=(-x*s+z*c)*scale+translation[2];
  out[i+3]=(tile.col+vertices[i+3]*tile.width/tile.size)/tile.grid;
  out[i+4]=(tile.row+vertices[i+4]*tile.height/tile.size)/tile.grid;
  out[i+5]=nx*c+nz*s;out[i+6]=vertices[i+6];out[i+7]=-nx*s+nz*c;
 }
 return out;
}
function bodyFloor(model,vertices){
 let minimum=Infinity;
 for(const part of model.parts||[])if(part.name.startsWith('soft_head_slice_')||part.name==='rounded_body_surface'||part.name==='house_body_surface'||part.name==='predator_body_surface'||part.name==='deficient_body_surface'){
  for(let index=part.start;index<part.start+part.count;index++)minimum=Math.min(minimum,vertices[index*8+1]);
 }
 return Number.isFinite(minimum)?minimum:0;
}
function careTarget(parent,child,q){
 const parentMouth=presentations[parent.model]?.mouth,childMouth=presentations[child.model]?.mouth;
 if(!parentMouth||!childMouth)throw Error('Care contact needs living.json mouth anchors');
 const parentScale=parent.scale===undefined?1:parent.scale,childScale=child.scale===undefined?1:child.scale;
 const parentYaw=(parent.yaw||0)*Math.PI/180,childYaw=(child.yaw||0)*Math.PI/180;
 const parentAt=actorPosition(parent,q),childAt=actorPosition(child,q);
 // Explicit offsets are in scene model units after the child's scale; first-toilet
 // assistance uses the physical rear point instead of the child's mouth.
 const point=parent.careTargetOffset||childMouth.map(value=>value*childScale);
 const [mouthX,mouthZ]=rotateXZ(point[0],point[2],childYaw);
 const [x,z]=inverseRotateXZ(childAt[0]+mouthX-parentAt[0],childAt[2]+mouthZ-parentAt[2],parentYaw);
 return {anchor:parentMouth,target:[x/parentScale,(childAt[1]+point[1]-parentAt[1])/parentScale,z/parentScale]};
}
function groundedCarePose(pose,action,time,anchor,target,blend=1){
 // Mirrors YukkuriModel: source and contact tracking share the same total pitch
 // budget. Feeding remains a slight inclination even beside the smallest child.
 const model=pose.model,baseline=pose.frame(action,time),floor=bodyFloor(model,baseline);
 const feeding=action!=='first_toilet_assist',maximumPitch=feeding?10:28,sourcePitch=pose.rootPose[3];
 const basePitch=Math.max(-maximumPitch,sourcePitch),clampPitch=basePitch-sourcePitch;
 pose.frame(action,time,{pitch:clampPitch});const baseMouth=pose.rootPoint(anchor);
 const goal=baseMouth.map((value,index)=>value+(target[index]-value)*blend);
 let pitch=0;
 if(baseMouth[1]>goal[1]){
  let low=0,high=Math.max(0,maximumPitch+basePitch)*blend;
  for(let i=0;i<7;i++){
   const candidate=(low+high)*.5;pose.frame(action,time,{pitch:clampPitch-candidate});
   const lifted=Math.max(0,floor-bodyFloor(model,pose.output));
   if(pose.rootPoint(anchor)[1]+lifted>goal[1])low=candidate;else high=candidate;
  }
  pitch=high;
 }
 pose.frame(action,time,{pitch:clampPitch-pitch});
 const mouth=pose.rootPoint(anchor),lateral=feeding?.02:.04,forward=feeding?.03:.18;
 const x=Math.max(-lateral*16,Math.min(lateral*16,(target[0]-mouth[0])*blend)),z=Math.max(-forward*16,Math.min(forward*16,(target[2]-mouth[2])*blend));
 pose.frame(action,time,{pitch:clampPitch-pitch,translation:[x,0,z]});
 const lift=Math.max(0,floor-bodyFloor(model,pose.output));
 return pose.frame(action,time,{pitch:clampPitch-pitch,translation:[x,lift,z]});
}
function withoutAccessory(model,vertices){
 // A HatBoat holds the owner's one real hat.  Preview the owner bare-headed,
 // matching the stowed-accessory state used while the turned hat is afloat.
 const prefixes=presentations[model.id]?.accessory_prefixes||[];
 const kept=[];
 for(const part of model.parts||[])if(!prefixes.some(prefix=>part.name.startsWith(prefix)))kept.push(vertices.slice(part.start*8,(part.start+part.count)*8));
 const out=new Float64Array(kept.reduce((length,part)=>length+part.length,0));
 let offset=0;for(const part of kept){out.set(part,offset);offset+=part.length;}
 return out;
}
function propVertices(prop,tile,q){
 const data=readJson(prop.source),raw=Float64Array.from(data.vertices),position=interpolate(prop.from||[prop.x||0,prop.y||0,prop.z||0],prop.to,q);
 let base=raw;
 if(Array.isArray(prop.parts)){
  const wanted=new Set(prop.parts),visible=[];
  for(const part of data.parts||[])if(wanted.has(part.name))visible.push(raw.slice(part.start*8,(part.start+part.count)*8));
  base=new Float64Array(visible.reduce((length,part)=>length+part.length,0));
  let offset=0;for(const part of visible){base.set(part,offset);offset+=part.length;}
 } else if(Number.isInteger(prop.layers)){
  const visible=[];
  for(const part of data.parts||[]){
   const unit=part.unit||0;
   if(unit===0||(prop.layers&(1<<(unit-1)))!==0)visible.push(raw.slice(part.start*8,(part.start+part.count)*8));
  }
  base=new Float64Array(visible.reduce((length,part)=>length+part.length,0));
  let offset=0;for(const part of visible){base.set(part,offset);offset+=part.length;}
 }
 if(prop.boat){
  // HatBoatEntity keeps the turned hat fixed under a seated owner.  Reuse that
  // vessel transform so an offline water scene shows a real supporting bowl,
  // rather than a loose hat or a generic platform.
  const vessel=readJson('src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json');
  const k=vessel.aquatic_scale,squash=vessel.squash.bowl;
  const seat=[0,vessel.bowl.owner_y*k,vessel.opening[3]*k-5.9];
  base=Float64Array.from(base);
  for(let i=0;i<base.length;i+=8){
   base[i]=base[i]*k-seat[0];base[i+1]=base[i+1]*k*squash+vessel.bowl.freeboard*k-seat[1];base[i+2]=base[i+2]*k-seat[2];
   base[i+5]/=k;base[i+6]/=(k*squash);base[i+7]/=k;
   const n=Math.hypot(base[i+5],base[i+6],base[i+7])||1;base[i+5]/=n;base[i+6]/=n;base[i+7]/=n;
  }
 }
 return transform(base,position,prop.scale===undefined?1:prop.scale,(prop.yaw||0)*Math.PI/180,tile);
}
function sceneEntries(clip,time){
 const q=clip.duration?Math.min(1,Math.max(0,time/clip.duration)):0,entries=[];
 for(const baseActor of clip.actors){
  const phase=(baseActor.phases||[]).find(p=>time>=p.start&&(p.end===undefined||time<p.end));
  const actor=phase?{...baseActor,...phase}:baseActor;
  const localTime=phase?time-phase.start:time;
  const pose=poseFor(actor.model);if(!pose.clips[actor.action])throw Error('Unknown action '+actor.model+'/'+actor.action);
  const actionTime=Math.min(actor.maxTime??Infinity,localTime*(actor.speed??1)+(actor.timeOffset??0)),face=actor.face||clip.face||pose.expressionFor(actor.action,actionTime);
  const key='entity:'+actor.model+':'+face;rawTexture(key);pose.expression(face);
  let vertices,anchor=presentations[actor.model]?.mouth;
  if(Number.isInteger(actor.careTarget)){
   const child=clip.actors[actor.careTarget];if(!child)throw Error(`Unknown care target ${actor.careTarget}`);
   const contact=careTarget(actor,child,q);anchor=contact.anchor;vertices=groundedCarePose(pose,actor.action,actionTime,contact.anchor,contact.target,actor.careProgress??1);
  }else vertices=pose.frame(actor.action,actionTime).slice();
  // RuntimePose owns one output buffer per model; keep this actor's sampled
  // result before the next actor with the same model samples into that buffer.
  vertices=vertices.slice();
  if(actor.accessory===false)vertices=withoutAccessory(pose.model,vertices);
  entries.push({vertices,translation:actorPosition(actor,q),scale:actor.scale===undefined?1:actor.scale,yaw:(actor.yaw||0)*Math.PI/180,key,mouth:anchor?scenePoint(actor,pose.rootPoint(anchor),q):null});
 }
 for(const prop of clip.props||[]){
  if(time<(prop.start??0)||time>=(prop.end??Infinity))continue;
  const progress=prop.start!==undefined||prop.end!==undefined?Math.min(1,Math.max(0,(time-(prop.start??0))/Math.max(.001,(prop.end??clip.duration)-(prop.start??0)))):q;
  let placed=prop;
  if(Number.isInteger(prop.mouthActor)){
   const mouth=entries[prop.mouthActor]?.mouth;if(!mouth)throw Error(`Missing mouth anchor for actor ${prop.mouthActor}`);
   const {from,to,...anchored}=prop;placed={...anchored,x:mouth[0],y:mouth[1],z:mouth[2]};
  }
  rawTexture(placed.textureKey);entries.push({prop:placed,key:placed.textureKey,q:progress});
 }
 return entries;
}
function buildAtlas(entries){
 const keys=[...new Set(entries.map(e=>e.key))],sources=keys.map(rawTexture),tileSize=Math.max(...sources.map(x=>Math.max(x.width,x.height))),grid=Math.ceil(Math.sqrt(keys.length)),size=tileSize*grid,raw=Buffer.alloc(size*size*4),tiles=new Map();
 sources.forEach((source,index)=>{const col=index%grid,row=Math.floor(index/grid);tiles.set(keys[index],{col,row,grid,size:tileSize,width:source.width,height:source.height});for(let y=0;y<source.height;y++)source.raw.copy(raw,((row*tileSize+y)*size+col*tileSize)*4,y*source.width*4,(y+1)*source.width*4);});
 return {raw,size,tiles};
}
function composite(clip,time){
 const entries=sceneEntries(clip,time),atlas=buildAtlas(entries),all=[];
 for(const entry of entries){const tile=atlas.tiles.get(entry.key);all.push(entry.prop?propVertices(entry.prop,tile,entry.q):transform(entry.vertices,entry.translation,entry.scale,entry.yaw,tile));}
 const length=all.reduce((n,v)=>n+v.length,0),vertices=new Float64Array(length);let offset=0;for(const v of all){vertices.set(v,offset);offset+=v.length;}
 return {vertices,texture:atlas.raw,textureSize:atlas.size};
}
function bounds(vertices){let low=Infinity,high=-Infinity,radius=0;for(let i=0;i<vertices.length;i+=8){low=Math.min(low,vertices[i+1]);high=Math.max(high,vertices[i+1]);radius=Math.max(radius,Math.hypot(vertices[i],vertices[i+2]));}return {low,high,radius};}
function cameraFor(clip){let low=Infinity,high=-Infinity,radius=0;for(let frame=0;frame<tasks.frames;frame++){const scene=composite(clip,clip.duration*frame/(tasks.frames-1));const b=bounds(scene.vertices);low=Math.min(low,b.low);high=Math.max(high,b.high);radius=Math.max(radius,b.radius);}const configured=clip.camera||{};return {azimuth:configured.azimuth??.45,elevation:configured.elevation??.15,centerY:configured.centerY??(low+high)/2,span:configured.span??Math.max((high-low)*.66,radius*.95,4),bounds:{low,high,radius}};}
const summary={clips:[]};
for(const clip of tasks.clips){
 const camera=cameraFor(clip),frames=[];
 for(let frame=0;frame<tasks.frames;frame++){const scene=composite(clip,clip.duration*frame/(tasks.frames-1));const pixels=raster.renderModel(scene.vertices,scene.texture,tasks.width,tasks.height,camera.azimuth,camera.elevation,camera.span,{centerY:camera.centerY,textureSize:scene.textureSize});const output=path.join(tasks.work,clip.id+'-'+String(frame).padStart(3,'0')+'.rgba');fs.writeFileSync(output,Buffer.from(pixels));frames.push(output);}
 summary.clips.push({id:clip.id,frames,camera});
}
fs.writeFileSync(path.join(tasks.work,'render-result.json'),JSON.stringify(summary,null,2));
console.log('Rendered '+summary.clips.length+' action clips x '+tasks.frames+' frames.');
