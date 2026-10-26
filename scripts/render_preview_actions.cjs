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
const adultStates=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/adult_states.json'),'utf8'));
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const poses=new Map(),models=new Map(),textures=new Map();
const vessel=readJson('src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json');
const deckLayout=readJson('src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_deck_layout.json');
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
function keyed(track,time,name,fallback){
 const points=(track||[]).filter(point=>point[name]!==undefined);
 if(!points.length)return fallback;
 let before=points[0],after=points[points.length-1];
 for(const point of points){if(point.at<=time)before=point;if(point.at>=time){after=point;break;}}
 if(before===after||after.at===before.at)return before[name];
 const q=(time-before.at)/(after.at-before.at),left=before[name],right=after[name];
 return Array.isArray(left)?left.map((value,index)=>value+(right[index]-value)*q):left+(right-left)*q;
}
function actorState(actor,q,time,clip){
 const position=keyed(actor.keyframes,time,'position',interpolate(actor.from||[actor.x||0,actor.y||0,actor.z||0],actor.to,q));
 let yaw=keyed(actor.keyframes,time,'yaw',actor.yaw||0);
 if(!actor.boatSeat)return {position,yaw,scale:keyed(actor.keyframes,time,'scale',actor.scale===undefined?1:actor.scale)};
 const prop=clip?.props?.[actor.boatSeat.prop];if(!prop?.boat)throw Error('boatSeat needs a boat prop index');
 const slot=deckLayout.modes.AQUATIC_FLOAT.slots.find(item=>item.id===actor.boatSeat.slot);
 if(!slot)throw Error('Unknown aquatic boat seat '+actor.boatSeat.slot);
 const hull=propState(prop,q,time),turn=hull.yaw*Math.PI/180;
 const [x,z]=rotateXZ(slot.pos[0]*vessel.aquatic_scale,slot.pos[2]*vessel.aquatic_scale,turn);
 return {position:[hull.position[0]+x,hull.position[1]+slot.pos[1]*vessel.aquatic_scale,hull.position[2]+z],yaw:hull.yaw+slot.yaw,scale:keyed(actor.keyframes,time,'scale',actor.scale===undefined?1:actor.scale)};
}
function propState(prop,q,time){
 const position=keyed(prop.keyframes,time,'position',interpolate(prop.from||[prop.x||0,prop.y||0,prop.z||0],prop.to,q));
 return {position,yaw:keyed(prop.keyframes,time,'yaw',prop.yaw||0),scale:keyed(prop.keyframes,time,'scale',prop.scale===undefined?1:prop.scale)};
}
function actorAt(clip,index,time){
 const base=clip.actors[index];if(!base)throw Error(`Unknown actor ${index}`);
 if(time<(base.start??0)||time>=(base.end??Infinity))return null;
 const phase=(base.phases||[]).find(item=>time>=item.start&&(item.end===undefined||time<item.end));
 return phase?{...base,...phase,_phaseStart:phase.start}:base;
}
function statePoint(state,point){
 const at=state.position,scale=state.scale,yaw=state.yaw*Math.PI/180;
 const [x,z]=rotateXZ(point[0]*scale,point[2]*scale,yaw);return [at[0]+x,at[1]+point[1]*scale,at[2]+z];
}
function scenePoint(actor,point,q,time,clip){return statePoint(actorState(actor,q,time,clip),point);}
function transformedPoint(matrix,point){
 return [matrix[0]*point[0]+matrix[1]*point[1]+matrix[2]*point[2]+matrix[3],matrix[4]*point[0]+matrix[5]*point[1]+matrix[6]*point[2]+matrix[7],matrix[8]*point[0]+matrix[9]*point[1]+matrix[10]*point[2]+matrix[11]];
}
function contactState(actor,state,time,samples){
 const follow=actor.contactFollow;
 if(!follow||time<follow.start||time>follow.end)return state;
 const source=samples.get(follow.actor);if(!source)throw Error(`Missing contact source actor ${follow.actor}`);
 const contact=source.pose.clips[source.actor.action]?.contacts?.[follow.contact];
 if(!contact)throw Error(`Missing ${follow.contact} contact on ${source.actor.action}`);
 const bone=source.pose.model.bones.findIndex(item=>item.name===contact.bone);
 if(bone<0||!source.pose.world)throw Error(`Missing contact bone ${contact.bone}`);
 const local=transformedPoint(source.pose.world[bone],contact.point);
 const [x,z]=rotateXZ(local[0]*source.state.scale,local[2]*source.state.scale,source.state.yaw*Math.PI/180);
 const offset=follow.offset||[0,0,0];
 return {...state,position:[source.state.position[0]+x+offset[0],source.state.position[1]+local[1]*source.state.scale+offset[1],source.state.position[2]+z+offset[2]]};
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
function careTarget(parent,child,q,time,clip){
 const parentMouth=presentations[parent.model]?.mouth,childMouth=presentations[child.model]?.mouth;
 if(!parentMouth||!childMouth)throw Error('Care contact needs living.json mouth anchors');
 const parentState=actorState(parent,q,time,clip),childState=actorState(child,q,time,clip);
 const parentScale=parentState.scale,childScale=childState.scale;
 const parentYaw=parentState.yaw*Math.PI/180,childYaw=childState.yaw*Math.PI/180;
 const parentAt=parentState.position,childAt=childState.position;
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
function actorPerformance(pose,actor,vertices,time){
 if(actor.anatomy){
  const extended=pose.frame('anatomy_'+actor.anatomy+'_extend',Math.min(.24,Math.max(0,time-(actor._phaseStart??0))));
  for(const part of pose.model.parts||[])if(part.anatomy===actor.anatomy){
   vertices.set(extended.subarray(part.start*8,(part.start+part.count)*8),part.start*8);
   if(actor.anatomy==='mamu'&&actor.adultPose)for(let i=part.start*8;i<(part.start+part.count)*8;i+=8){
    // Reproduction uses the rear while the unchanged birth rig keeps its front outlet.
    vertices[i]=-vertices[i];vertices[i+2]=-vertices[i+2]+adultStates.mamu_rear_offset;vertices[i+5]=-vertices[i+5];vertices[i+7]=-vertices[i+7];
   }
  }
 }
 if(!actor.adultPose)return vertices;
 const keys=adultStates[actor.adultPose];if(!keys)throw Error('Unknown adult pose '+actor.adultPose);
 const progress=((time%1)+1)%1;let index=0;
 while(index+2<keys.length&&progress>keys[index+1][0])index++;
 const a=keys[index],b=keys[index+1],q=Math.max(0,Math.min(1,(progress-a[0])/(b[0]-a[0]))),m=q*q*(3-2*q);
 const v=a.slice(1).map((x,k)=>x+(b[k+1]-x)*m),rad=Math.PI/180;
 const cp=Math.cos(v[0]*rad),sp=Math.sin(v[0]*rad),cy=Math.cos(v[1]*rad),sy=Math.sin(v[1]*rad),cr=Math.cos(v[2]*rad),sr=Math.sin(v[2]*rad);
 const floor=bodyFloor(pose.model,vertices);
 for(let i=0;i<vertices.length;i+=8){
  const x=vertices[i]*v[3],y=vertices[i+1]*v[4],z=vertices[i+2]*v[5];
  const y1=y*cp-z*sp,z1=y*sp+z*cp,x2=x*cy+z1*sy,z2=-x*sy+z1*cy;
  vertices[i]=x2*cr-y1*sr;vertices[i+1]=x2*sr+y1*cr;vertices[i+2]=z2;
  const nx=vertices[i+5],ny=vertices[i+6],nz=vertices[i+7],ny1=ny*cp-nz*sp,nz1=ny*sp+nz*cp,nx2=nx*cy+nz1*sy,nz2=-nx*sy+nz1*cy;
  vertices[i+5]=nx2*cr-ny1*sr;vertices[i+6]=nx2*sr+ny1*cr;vertices[i+7]=nz2;
 }
 const lift=Math.max(0,floor-bodyFloor(pose.model,vertices));
 for(let i=1;i<vertices.length;i+=8)vertices[i]+=lift;
 return vertices;
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
function propVertices(prop,tile,q,time){
 const data=readJson(prop.source),raw=Float64Array.from(data.vertices),state=propState(prop,q,time),position=state.position;
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
  const k=vessel.aquatic_scale,squash=vessel.squash.bowl;
  // New scene manifests anchor a hull at its actual waterline origin and place
  // riders by named `hat_deck_layout` slots. The older action manifests keep
  // their captain-relative transform for compatibility with prior previews.
  const seat=prop.boatAnchor==='hull'?[0,0,0]:[0,vessel.bowl.owner_y*k,vessel.opening[3]*k-5.9];
  base=Float64Array.from(base);
  for(let i=0;i<base.length;i+=8){
   base[i]=base[i]*k-seat[0];base[i+1]=base[i+1]*k*squash+vessel.bowl.freeboard*k-seat[1];base[i+2]=base[i+2]*k-seat[2];
   base[i+5]/=k;base[i+6]/=(k*squash);base[i+7]/=k;
   const n=Math.hypot(base[i+5],base[i+6],base[i+7])||1;base[i+5]/=n;base[i+6]/=n;base[i+7]/=n;
  }
 }
 return transform(base,position,state.scale,state.yaw*Math.PI/180,tile);
}
function sceneEntries(clip,time){
 const q=clip.duration?Math.min(1,Math.max(0,time/clip.duration)):0,entries=[],actorMouths=new Map(),actorSamples=new Map();
 for(let actorIndex=0;actorIndex<clip.actors.length;actorIndex++){
  const actor=actorAt(clip,actorIndex,time);if(!actor)continue;
  const localTime=actor._phaseStart===undefined?time:time-actor._phaseStart;
  const pose=poseFor(actor.model);if(!pose.clips[actor.action])throw Error('Unknown action '+actor.model+'/'+actor.action);
  const actionTime=Math.min(actor.maxTime??Infinity,localTime*(actor.speed??1)+(actor.timeOffset??0)),face=actor.face||clip.face||pose.expressionFor(actor.action,actionTime);
  const key='entity:'+actor.model+':'+face;rawTexture(key);pose.expression(face);
  const state=contactState(actor,actorState(actor,q,time,clip),time,actorSamples);
  let vertices,anchor=presentations[actor.model]?.mouth;
  if(Number.isInteger(actor.careTarget)){
   const child=actorAt(clip,actor.careTarget,time);if(!child)throw Error(`Inactive care target ${actor.careTarget}`);
   const contact=careTarget(actor,child,q,time,clip);anchor=contact.anchor;vertices=groundedCarePose(pose,actor.action,actionTime,contact.anchor,contact.target,actor.careProgress??1);
  }else vertices=pose.frame(actor.action,actionTime).slice();
  vertices=actorPerformance(pose,actor,vertices,time);
  // RuntimePose owns one output buffer per model; keep this actor's sampled
  // result before the next actor with the same model samples into that buffer.
  vertices=vertices.slice();
  if(actor.accessory===false)vertices=withoutAccessory(pose.model,vertices);
  const mouth=anchor?statePoint(state,pose.rootPoint(anchor)):null;
  entries.push({vertices,translation:state.position,scale:state.scale,yaw:state.yaw*Math.PI/180,key,mouth});
  actorMouths.set(actorIndex,mouth);
  actorSamples.set(actorIndex,{actor,pose,state});
 }
 for(const prop of clip.props||[]){
  if(time<(prop.start??0)||time>=(prop.end??Infinity))continue;
  const progress=prop.start!==undefined||prop.end!==undefined?Math.min(1,Math.max(0,(time-(prop.start??0))/Math.max(.001,(prop.end??clip.duration)-(prop.start??0)))):q;
  let placed=prop;
  if(Number.isInteger(prop.mouthActor)){
   const mouth=actorMouths.get(prop.mouthActor);if(!mouth)throw Error(`Missing mouth anchor for actor ${prop.mouthActor}`);
   const {from,to,...anchored}=prop;placed={...anchored,x:mouth[0],y:mouth[1],z:mouth[2]};
  }else if(Number.isInteger(prop.fromMouthActor)||Number.isInteger(prop.toMouthActor)){
   const from=actorMouths.get(prop.fromMouthActor),to=actorMouths.get(prop.toMouthActor);
   if(!from||!to)throw Error(`Missing transfer mouth anchor for actors ${prop.fromMouthActor}/${prop.toMouthActor}`);
   const {fromMouthActor,toMouthActor,keyframes,...transfer}=prop;placed={...transfer,from,to};
  }
  rawTexture(placed.textureKey);entries.push({prop:placed,key:placed.textureKey,q:progress,time});
 }
 return entries;
}
function buildAtlas(entries){
 const keys=[...new Set(entries.map(e=>e.key))],sources=keys.map(rawTexture),tileSize=Math.max(...sources.map(x=>Math.max(x.width,x.height))),grid=Math.ceil(Math.sqrt(keys.length)),size=tileSize*grid,raw=Buffer.alloc(size*size*4),tiles=new Map();
 sources.forEach((source,index)=>{const col=index%grid,row=Math.floor(index/grid);tiles.set(keys[index],{col,row,grid,size:tileSize,width:source.width,height:source.height});for(let y=0;y<source.height;y++)source.raw.copy(raw,((row*tileSize+y)*size+col*tileSize)*4,y*source.width*4,(y+1)*source.width*4);});
 return {raw,size,tiles};
}
const ENV_COLORS=[[117,183,205],[170,210,221],[205,191,126],[107,142,85],[91,128,79],[227,232,206],[239,246,247],[248,248,242]];
function solidVertex(x,y,z,color){return [x,y,z,-1-color,0,0,1,0];}
function solidQuad(x0,z0,x1,z1,y,color){
 const a=solidVertex(x0,y,z0,color),b=solidVertex(x0,y,z1,color),c=solidVertex(x1,y,z1,color),d=solidVertex(x1,y,z0,color);
 return Float64Array.from([...a,...b,...c,...a,...c,...d]);
}
function append(out,mesh){out.push(mesh);}
function waterAroundHull(all,clip,time,x0,z0,x1,z1,y,color){
 const hull=(clip.props||[]).find(prop=>prop.boat&&prop.boatAnchor==='hull');
 if(!hull){append(all,solidQuad(x0,z0,x1,z1,y,color));return;}
 const state=propState(hull,clip.duration?time/clip.duration:0,time);
 const halfX=Math.abs(vessel.opening[1])*vessel.aquatic_scale+.6;
 const halfZ=Math.abs(vessel.opening[3])*vessel.aquatic_scale+.6;
 const hx0=Math.max(x0,state.position[0]-halfX),hx1=Math.min(x1,state.position[0]+halfX);
 const hz0=Math.max(z0,state.position[2]-halfZ),hz1=Math.min(z1,state.position[2]+halfZ);
 if(hx0>=hx1||hz0>=hz1){append(all,solidQuad(x0,z0,x1,z1,y,color));return;}
 if(x0<hx0)append(all,solidQuad(x0,z0,hx0,z1,y,color));
 if(hx1<x1)append(all,solidQuad(hx1,z0,x1,z1,y,color));
 if(z0<hz0)append(all,solidQuad(hx0,z0,hx1,hz0,y,color));
 if(hz1<z1)append(all,solidQuad(hx0,hz1,hx1,z1,y,color));
}
function environmentMesh(spec,time,clip){
 if(!spec)return new Float64Array();
 const all=[],kind=spec.kind;
 if(kind==='shore-voyage'){
  waterAroundHull(all,clip,time,-56,-52,56,9,-1.4,0);
  append(all,solidQuad(-56,9,56,52,-.8,2));
  append(all,solidQuad(-56,18,-8,52,-.72,3));
  append(all,solidQuad(19,16,56,52,-.70,4));
  // Reeds and pale sand shelves make the launch point legible while remaining
  // simple terrain geometry, not a second authored art source.
  for(const [x,z] of [[-39,14],[-32,18],[-25,12],[31,18],[39,24]])append(all,solidQuad(x,z,x+2,z+7,-.45,4));
 }else if(kind==='forest-nest'){
  append(all,solidQuad(-54,-54,54,54,-1.1,3));
  append(all,solidQuad(-31,-25,31,30,-.92,5));
  append(all,solidQuad(-15,-13,15,14,-.72,2));
  append(all,solidQuad(-11,-10,11,11,-.62,5));
  for(const [x,z] of [[-28,-8],[-23,12],[20,-5],[25,14],[-12,25],[12,23]])append(all,solidQuad(x,z,x+6,z+5,-.82,4));
 }else if(kind==='water-rescue'){
  waterAroundHull(all,clip,time,-58,-56,58,56,-1.45,0);
  append(all,solidQuad(28,6,58,56,-.78,2));
  append(all,solidQuad(35,16,58,56,-.70,3));
  // Moving shallow wave strips are part of the terrain pass and give the water
  // a continuous read across the rescue, without inventing a texture asset.
  for(let index=0;index<7;index++){
   const x=-50+index*15,z=-28+((time*6+index*11)%42);
   waterAroundHull(all,clip,time,x,z,x+8,z+.7,-1.22,1);
  }
 }
 const length=all.reduce((total,mesh)=>total+mesh.length,0),vertices=new Float64Array(length);let offset=0;
 for(const mesh of all){vertices.set(mesh,offset);offset+=mesh.length;}
 return vertices;
}
function composite(clip,time,includeEnvironment=false){
 const entries=sceneEntries(clip,time),atlas=buildAtlas(entries),all=[];
 if(includeEnvironment)all.push(environmentMesh(clip.environment,time,clip));
 for(const entry of entries){const tile=atlas.tiles.get(entry.key);all.push(entry.prop?propVertices(entry.prop,tile,entry.q,entry.time):transform(entry.vertices,entry.translation,entry.scale,entry.yaw,tile));}
 const length=all.reduce((n,v)=>n+v.length,0),vertices=new Float64Array(length);let offset=0;for(const v of all){vertices.set(v,offset);offset+=v.length;}
 return {vertices,texture:atlas.raw,textureSize:atlas.size,colors:ENV_COLORS};
}
function bounds(vertices){let low=Infinity,high=-Infinity,radius=0;for(let i=0;i<vertices.length;i+=8){low=Math.min(low,vertices[i+1]);high=Math.max(high,vertices[i+1]);radius=Math.max(radius,Math.hypot(vertices[i],vertices[i+2]));}return {low,high,radius};}
function cameraFor(clip){let low=Infinity,high=-Infinity,radius=0;for(let frame=0;frame<tasks.frames;frame++){const scene=composite(clip,clip.duration*frame/(tasks.frames-1));const b=bounds(scene.vertices);low=Math.min(low,b.low);high=Math.max(high,b.high);radius=Math.max(radius,b.radius);}const configured=clip.camera||{};return {azimuth:configured.azimuth??.45,elevation:configured.elevation??.15,centerY:configured.centerY??(low+high)/2,span:configured.span??Math.max((high-low)*.66,radius*.95,4),bounds:{low,high,radius}};}
const summary={clips:[]};
for(const clip of tasks.clips){
 const camera=cameraFor(clip),frames=[];
 for(let frame=0;frame<tasks.frames;frame++){const scene=composite(clip,clip.duration*frame/(tasks.frames-1),true);const pixels=raster.renderModel(scene.vertices,scene.texture,tasks.width,tasks.height,camera.azimuth,camera.elevation,camera.span,{centerY:camera.centerY,textureSize:scene.textureSize,colors:scene.colors});const output=path.join(tasks.work,clip.id+'-'+String(frame).padStart(3,'0')+'.rgba');fs.writeFileSync(output,Buffer.from(pixels));frames.push(output);}
 summary.clips.push({id:clip.id,frames,camera});
}
fs.writeFileSync(path.join(tasks.work,'render-result.json'),JSON.stringify(summary,null,2));
console.log('Rendered '+summary.clips.length+' action clips x '+tasks.frames+' frames.');
