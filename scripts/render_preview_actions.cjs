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
function propVertices(prop,tile,q){
 const data=readJson(prop.source),base=Float64Array.from(data.vertices),position=interpolate(prop.from||[prop.x||0,prop.y||0,prop.z||0],prop.to,q);
 return transform(base,position,prop.scale===undefined?1:prop.scale,(prop.yaw||0)*Math.PI/180,tile);
}
function sceneEntries(clip,time){
 const q=clip.duration?Math.min(1,Math.max(0,time/clip.duration)):0,entries=[];
 for(const actor of clip.actors){
  const pose=poseFor(actor.model);if(!pose.clips[actor.action])throw Error('Unknown action '+actor.model+'/'+actor.action);
  const actionTime=Math.min(actor.maxTime??Infinity,time*(actor.speed??1)+(actor.timeOffset??0)),face=actor.face||clip.face||pose.expressionFor(actor.action,actionTime);
  const key='entity:'+actor.model+':'+face;rawTexture(key);pose.expression(face);
  entries.push({vertices:pose.frame(actor.action,actionTime).slice(),translation:interpolate(actor.from||[actor.x||0,actor.y||0,actor.z||0],actor.to,q),scale:actor.scale===undefined?1:actor.scale,yaw:(actor.yaw||0)*Math.PI/180,key});
 }
 for(const prop of clip.props||[]){rawTexture(prop.textureKey);entries.push({prop,key:prop.textureKey,q});}
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
