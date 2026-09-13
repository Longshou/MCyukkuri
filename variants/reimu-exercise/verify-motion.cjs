const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{poseVertices}=require('./motion.js');
const read=p=>JSON.parse(fs.readFileSync(path.join(__dirname,p),'utf8'));
const data=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);
const bb=read('reimu.bbmodel'),source=read('reuse/marisa-animation-rig.json');
const sample=(keys,t,def)=>{const a=Object.entries(keys||{}).map(([k,v])=>[Number(k),v]).sort((a,b)=>a[0]-b[0]);if(!a.length)return def;if(t<=a[0][0])return a[0][1];for(let i=1;i<a.length;i++){if(t<=a[i][0]){const [ta,x]=a[i-1],[tb,y]=a[i],q=(t-ta)/(tb-ta);return x.map((v,k)=>v+(y[k]-v)*q)}}return a.at(-1)[1]};
const defs={rotation:[0,0,0],position:[0,0,0],scale:[1,1,1]};
let reuseError=0;
for(const clip of ['exercise_vertical','exercise_sides','stretch']){
 assert.equal(data.rig.clips[clip].duration,source.clips[clip].duration);
 for(const [n,channels] of Object.entries(data.rig.clips[clip].tracks)){
  if(!source.clips[clip].tracks[n])continue;
  for(let i=0;i<=40;i++)for(const ch of Object.keys(channels)){
   const t=i*data.rig.clips[clip].duration/40,a=sample(channels[ch],t,defs[ch]),b=sample(source.clips[clip].tracks[n][ch],t,defs[ch]).slice();
   if(n.startsWith('soft_head_slice_')&&ch==='scale'){b[0]=1-(1-b[0])*.5;b[2]=1;}
   for(let k=0;k<3;k++)reuseError=Math.max(reuseError,Math.abs(a[k]-b[k]));
  }
 }
}
assert(reuseError<.00003);
const skinGroups={};bb.elements.forEach((e,i)=>{if(e.name.includes('_slice_'))(skinGroups[e.name.split('_slice_')[0]]??=[]).push(i)});
let minY=Infinity,maxY=-Infinity,separation=0,closureError=0,rumpOverlap=Infinity,facePlaneOffset=0;const checks=[];
const facialIds=bb.elements.flatMap((e,i)=>e.name.startsWith('face_rest_')||e.name.startsWith('face_effort_')?[i]:[]);
const bounds=(v,ids)=>{const lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];for(const id of ids)for(let k=id*288;k<(id+1)*288;k+=8)for(let a=0;a<3;a++){lo[a]=Math.min(lo[a],v[k+a]);hi[a]=Math.max(hi[a],v[k+a]);}return {lo,hi};};
assert(data.rig.bones.some(b=>b.name==='rump'&&b.parent==='rump_roll'));
assert(Object.values(data.rig.clips.press.tracks.rump_sway.position).some(v=>Math.abs(v[0])>.4));
for(const [name,clip] of Object.entries(data.rig.clips)){
 for(let frame=0;frame<=Math.round(clip.duration*50);frame++){
  const time=Math.min(clip.duration,frame*.02),v=poseVertices(data.vertices,data.rig,time,true,false,name);assert(v.every(Number.isFinite));
  for(let i=0;i<v.length;i+=8){minY=Math.min(minY,v[i+1]);maxY=Math.max(maxY,v[i+1]);}
  const p0=skinGroups.soft_head[0]*288;
  for(const id of facialIds){
   const variant=bb.elements[id].name.startsWith('face_rest_')?'face_rest':'face_effort';
   if(sample(clip.tracks[variant]?.scale,time,variant==='face_effort'?[.000001,.000001,.000001]:[1,1,1])[0]<.5)continue;
   for(let k=id*288;k<id*288+48;k+=8){let distance=0;for(let a=0;a<3;a++)distance+=(v[k+a]-v[p0+a])*v[p0+5+a];facePlaneOffset=Math.max(facePlaneOffset,Math.abs(distance));assert(distance>-.00001,'facial pigment behind skin');}
  }
  const front=bounds(v,skinGroups.soft_head),core=bounds(v,skinGroups.rump_core),end=bounds(v,skinGroups.rump_round_end);
  for(const [a,b] of [[front,core],[core,end]])for(let k=0;k<3;k++)rumpOverlap=Math.min(rumpOverlap,Math.min(a.hi[k],b.hi[k])-Math.max(a.lo[k],b.lo[k]));
  if(!['exercise_vertical','exercise_sides'].includes(name))continue;
  for(const ids of Object.values(skinGroups))for(let j=1;j<ids.length;j++){
   const axes=[];
   for(const id of [ids[j-1],ids[j]]){const n=data.rig.groups[id],angle=sample(clip.tracks[n].rotation,time,[0,0,0])[2]*Math.PI/180;axes.push([Math.cos(angle),Math.sin(angle)],[-Math.sin(angle),Math.cos(angle)])}
   for(const axis of axes){const bounds=ids.slice(j-1,j+1).map(id=>{let a=Infinity,b=-Infinity;for(let k=id*288;k<(id+1)*288;k+=8){const n=v[k]*axis[0]+v[k+1]*axis[1];a=Math.min(a,n);b=Math.max(b,n)}return [a,b]});separation=Math.max(separation,bounds[0][0]-bounds[1][1],bounds[1][0]-bounds[0][1]);}
  }
 }
 if(clip.loop){const a=poseVertices(data.vertices,data.rig,0,true,false,name),b=poseVertices(data.vertices,data.rig,clip.duration-1e-7,true,false,name);for(let i=0;i<a.length;i++)closureError=Math.max(closureError,Math.abs(a[i]-b[i]));}
 checks.push({clip:name,samples:Math.round(clip.duration*50)+1});
}
assert(minY>-.00001,`ground penetration ${minY}`);assert(separation<.00003,`skin gap ${separation}`);assert(closureError<.0001);
assert(rumpOverlap>0,`disconnected rump bounding volumes ${rumpOverlap}`);
assert(facePlaneOffset<.0031,`facial pigment detached from skin plane ${facePlaneOffset}`);
// The far bow corner rises above the 14.35-unit upright extension when tilted.
assert(maxY>14&&maxY<18.5,`unexpected animated height ${maxY}`);
const json={status:'passed',cubes:bb.elements.length,bones:data.rig.bones.length,reusedCurveMaxError:reuseError,minY,maxY,maxFaceSurfaceOffset:facePlaneOffset,minRumpBoundingOverlap:rumpOverlap,maxAdjacentSkinSeparation:separation,loopClosureError:closureError,checks};
fs.writeFileSync(path.join(__dirname,'motion-verification.json'),JSON.stringify(json,null,2));console.log(json);
