/** Export only numeric clips; never modifies either selected character geometry. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const source=path.dirname(fileURLToPath(import.meta.url));
const root=path.resolve(source,'../../..');
const recipe=JSON.parse(fs.readFileSync(path.join(source,'recipe.json'),'utf8'));
const output=path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/house_ecology.json');
const check=process.argv.includes('--check');
const smooth=q=>q*q*(3-2*q);
const round=value=>Number(value.toFixed(7));
function poseAt(keys,tick){
  let a=keys[0],b=keys.at(-1);
  for(let i=1;i<keys.length;i++)if(tick<=keys[i][0]){a=keys[i-1];b=keys[i];break;}
  const q=smooth(Math.max(0,Math.min(1,(tick-a[0])/Math.max(.001,b[0]-a[0]))));
  return a.slice(1).map((value,i)=>value+(b[i+1]-value)*q);
}
function floorLift(vertices,pose){
  const [pitch,yaw,roll,sx,sy,sz]=pose;
  const [rx,ry,rz]=[pitch,yaw,roll].map(v=>v*Math.PI/180);
  let floor=Infinity;
  // Matches Matrix4f.rotateZYX(...).scale(...) used by YukkuriModel.
  for(const vertex of vertices){
    const [x,y,z]=[vertex[0]*sx,vertex[1]*sy,vertex[2]*sz];
    const yy=y*Math.cos(rx)-z*Math.sin(rx),zz=y*Math.sin(rx)+z*Math.cos(rx);
    const xx=x*Math.cos(ry)+zz*Math.sin(ry);
    floor=Math.min(floor,xx*Math.sin(rz)+yy*Math.cos(rz));
  }
  return -floor;
}
const exported={format:1,source:'art/source/house-ecology-presentation/recipe.json',models:{}};
for(const id of recipe.models){
  const model=JSON.parse(fs.readFileSync(path.join(root,`src/main/resources/assets/mcyukkuri/yukkuri/${id}.json`),'utf8'));
  const rootIndex=model.bones.findIndex(b=>b.name==='root');
  if(rootIndex<0)throw new Error(`${id}: missing root`);
  const body=model.parts.find(p=>p.name==='house_body_surface');
  const vertices=Array.from({length:body.count},(_,i)=>model.vertices.slice((body.start+i)*8,(body.start+i)*8+3));
  const clips={};
  for(const [name,entry] of Object.entries(recipe.clips)){
    if(entry.keys[0][0]!==0||entry.keys.at(-1)[0]!==entry.duration_ticks)throw new Error(`Invalid duration: ${name}`);
    if(entry.loop&&JSON.stringify(entry.keys[0].slice(1))!==JSON.stringify(entry.keys.at(-1).slice(1)))throw new Error(`Loop seam: ${name}`);
    const tracks=['position','rotation','scale'].map(channel=>({bone:rootIndex,channel,keys:[]}));
    for(let tick=0;tick<=entry.duration_ticks;tick+=recipe.sample_ticks){
      const pose=poseAt(entry.keys,tick),t=tick/20,lift=floorLift(vertices,pose);
      tracks[0].keys.push([t,0,round(lift),0]);
      tracks[1].keys.push([t,...pose.slice(0,3).map(round)]);
      tracks[2].keys.push([t,...pose.slice(3).map(round)]);
      if(pose.some(v=>!Number.isFinite(v))||Math.abs(lift)>2)throw new Error(`Unsafe pose: ${id}/${name}/${tick}`);
    }
    clips[name]={duration:entry.duration_ticks/20,loop:entry.loop,tracks};
  }
  exported.models[id]={bone_names:model.bones.map(b=>b.name),clips};
}
const bytes=JSON.stringify(exported)+'\n';
if(check){
  if(!fs.existsSync(output)||fs.readFileSync(output,'utf8')!==bytes)throw new Error('house_ecology.json differs from recipe; run build.mjs');
}else fs.writeFileSync(output,bytes);
console.log(`${check?'Verified':'Exported'} ${recipe.models.length} house rigs × ${Object.keys(recipe.clips).length} grounded clips.`);
