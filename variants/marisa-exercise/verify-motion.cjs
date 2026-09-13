const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{poseVertices}=require('./motion.js');
const read=p=>JSON.parse(fs.readFileSync(path.join(__dirname,p),'utf8').replace(/^\uFEFF/,'')),bb=read('marisa.bbmodel'),d=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);
function bounds(v,ids){const min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];for(const i of ids)for(let k=i*288;k<(i+1)*288;k+=8)for(let a=0;a<3;a++){min[a]=Math.min(min[a],v[k+a]);max[a]=Math.max(max[a],v[k+a]);}return {min,max};}
const groups={};bb.elements.forEach((e,i)=>{const name=e.name.split('_slice_')[0];(groups[name]??=[]).push(i)});
const cache=new WeakMap();function interpolate(keys,t){let e=cache.get(keys);if(!e){e=Object.entries(keys).map(([t,v])=>[Number(t),v]).sort((a,b)=>a[0]-b[0]);cache.set(keys,e)}let j=0;while(j<e.length-2&&e[j+1][0]<t)j++;const a=e[j],b=e[j+1],q=Math.max(0,Math.min(1,(t-a[0])/(b[0]-a[0])));return a[1].map((v,k)=>v+(b[1][k]-v)*q);}
let minY=Infinity,maxHeight=0,maxSeparation=0;const results=[];
for(const clip of ['exercise_vertical','exercise_sides']){const c=d.rig.clips[clip];for(let frame=0;frame<Math.floor(c.duration*50);frame++){
 const t=frame*.02,v=poseVertices(d.vertices,d.rig,t,true,false,clip),all=bounds(v,bb.elements.map((_,i)=>i));minY=Math.min(minY,all.min[1]);maxHeight=Math.max(maxHeight,all.max[1]);assert(v.every(Number.isFinite));
 // Adjacent skin cross sections must overlap in every separating axis in their XY plane.
 for(const ids of Object.values(groups))for(let j=1;j<ids.length;j++){
  const ia=ids[j-1],ib=ids[j];if(!bb.elements[ia].name.includes('_slice_'))continue;
  const a=[],b=[];for(let k=ia*288;k<(ia+1)*288;k+=8)a.push([v[k],v[k+1]]);for(let k=ib*288;k<(ib+1)*288;k+=8)b.push([v[k],v[k+1]]);
  const axes=[];for(const i of [ia,ib]){const rot=interpolate(d.rig.clips[clip].tracks[bb.elements[i].name+'_bend'].rotation,t);const z=rot[2]*Math.PI/180;axes.push([Math.cos(z),Math.sin(z)],[-Math.sin(z),Math.cos(z)]);}
  for(const n of axes){const pa=a.map(p=>p[0]*n[0]+p[1]*n[1]),pb=b.map(p=>p[0]*n[0]+p[1]*n[1]);maxSeparation=Math.max(maxSeparation,Math.min(...pb)-Math.max(...pa),Math.min(...pa)-Math.max(...pb));}
 }
 }assert.deepEqual(poseVertices(d.vertices,d.rig,0,true,false,clip),poseVertices(d.vertices,d.rig,c.duration,true,false,clip));results.push({clip,samples:Math.floor(c.duration*50)});}
assert(minY>=-1e-5);assert(maxSeparation<1e-5);assert(maxHeight>=21.05-1e-5&&maxHeight<23);
const prev=JSON.parse(fs.readFileSync(path.join(__dirname,'../marisa-stretch-source/preview.html'),'utf8').match(/const DATA=(.*);/)[1]),oldBB=read('../marisa-stretch-source/marisa.bbmodel');const oldGroups={};oldBB.elements.forEach((e,i)=>(oldGroups[e.name.split('_slice_')[0]]??=[]).push(i));let waddleError=0;
for(let f=0;f<=24;f++){const a=poseVertices(d.vertices,d.rig,f*.05,true,false,'waddle'),b=poseVertices(prev.vertices,prev.rig,f*.05,true,false,'waddle');for(const name in groups){const u=bounds(a,groups[name]),v=bounds(b,oldGroups[name]);for(const side of ['min','max'])for(let k=0;k<3;k++)waddleError=Math.max(waddleError,Math.abs(u[side][k]-v[side][k]));}}
assert(waddleError<1e-6);const result={status:'passed',cubes:bb.elements.length,bones:d.rig.bones.length,minY,maxHeight,maxSkinSeparation:maxSeparation,waddleBoundsError:waddleError,results};fs.writeFileSync(path.join(__dirname,'motion-verification.json'),JSON.stringify(result,null,2));console.log(result);
