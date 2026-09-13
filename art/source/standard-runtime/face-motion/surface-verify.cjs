/* Diagnose house skin lighting using the current preview pipeline. No asset writes. */
const fs=require('fs'),path=require('path'),dir=process.argv[2],root=process.argv[3];
const lib=path.join(root,'standards/yukkuri-library');
const renderer=fs.readFileSync(path.join(lib,'runtime/render.js'),'utf8');
let sampler=fs.readFileSync(path.join(lib,'body-standard/previews/current/pose.js'),'utf8');
const smoothCall='smoothHouseSurface(model,out,surface);';
if(!sampler.includes(smoothCall))throw Error('Surface sampler changed; review the diagnosis adapter.');
sampler=sampler.replace('sample(model.clips.idle,0,poses);sample(model.clips[action],t,poses);',
 "sample(model.clips.idle,0,poses);if(action==='carry_waddle'){sample(model.clips.house_carry,t+.37,poses);sample(model.clips.waddle,t,poses);}else sample(model.clips[action],t,poses);");
sampler=sampler.replace('for(let first=part.start;first<part.start+part.count;first+=3){',
 "for(let first=part.start;first<part.start+part.count;first+=3){if(model.id.startsWith('house_')&&part.name.startsWith('face_')&&src[first*8+7]>=-.15)continue;");
const api=code=>new Function(renderer+'\n'+code+'\nreturn {posed,renderModel};')();
const current=api(sampler),legacy=api(sampler.replace(smoothCall,''));
const {models}=JSON.parse(fs.readFileSync(path.join(dir,'input.json')));
const result={status:'passed',pose_checks:0,ordinary_models_unchanged:[],models:{},scope:'House skin lighting; fixed geometry, UVs and animation; 25 phases per clip.'};
function assert(condition,message){if(!condition)throw Error(message);}
function groups(model,part){
 const v=model.vertices,groups=new Map();
 for(let i=part.start;i<part.start+part.count;i++){
  const k=[0,1,2].map(a=>Math.round(v[i*8+a]*100000)).join(',')+':'+[5,6,7].map(a=>Math.round(v[i*8+a]*1000)).join(',');
  if(!groups.has(k))groups.set(k,[]);groups.get(k).push((i-part.start)*8);
 }
 return [...groups.values()];
}
const difference=(v,a,b)=>Math.hypot(...[5,6,7].map(k=>v[a+k]-v[b+k]));
for(const [id,m]of Object.entries(models)){
 m.clips.carry_waddle=m.clips.waddle;
 const body=m.parts.find(p=>p.name==='house_body_surface'),bodyModel={...m,parts:[body]},refs=groups(m,body);
 let beforeJump=0,afterJump=0,positionUvChanges=0;
 for(const action of ['idle','waddle','carry_waddle','house_crawl','side_bend','stretch'])for(let phase=0;phase<=24;phase++){
  const t=m.clips[action].duration*phase/24;
  const before=legacy.posed(bodyModel,action,t),after=current.posed(bodyModel,action,t);
  assert(before.length===body.count*8&&before.length===after.length,id+' unexpected topology');
  for(let i=0;i<before.length;i++)if(i%8<5&&before[i]!==after[i])positionUvChanges++;
  for(const group of refs)for(const a of group)for(const b of group){beforeJump=Math.max(beforeJump,difference(before,a,b));afterJump=Math.max(afterJump,difference(after,a,b));}
  for(let i=0;i<after.length;i+=8)assert(Math.abs(Math.hypot(...after.slice(i+5,i+8))-1)<1e-8,id+' invalid normal');
  result.pose_checks++;
 }
 assert(positionUvChanges===0&&afterJump===0,id+' discontinuous skin');
 result.models[id]={groups:refs.length,max_shared_normal_jump_before:beforeJump,max_shared_normal_jump_after:afterJump,position_uv_changes:positionUvChanges};
 const texture=fs.readFileSync(path.join(dir,id+'-neutral.rgba'));
 for(let frame=0;frame<24;frame++)for(const az of [0,.55])for(const [name,engine]of [['before',legacy],['after',current]]){
  const pixels=engine.renderModel(engine.posed(m,'waddle',frame*.05),texture,460,400,az,.06,3.3,{centerY:2.4,textureSize:128});
  fs.writeFileSync(path.join(dir,`${id}-${name}-${az}-${frame}.rgba`),pixels);
 }
}
// The shared helper must leave all other gallery species' posed data unchanged.
const catalog=new Function(fs.readFileSync(path.join(lib,'body-standard/previews/current/all-models/models.js'),'utf8')+'\nreturn ALL_MODELS;')();
for(const [id,entry]of Object.entries(catalog))if(!id.startsWith('house_')){
 const m=entry.model;
 for(const phase of [0,.25,.75]){
  const action=entry.action,t=m.clips[action].duration*phase;
  const a=legacy.posed(m,action,t),b=current.posed(m,action,t);
  assert(a.length===b.length&&a.every((x,i)=>x===b[i]),id+' unrelated geometry/normal changed');
 }
 result.ordinary_models_unchanged.push(id);
}
fs.writeFileSync(path.join(dir,'result.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify(result));
