const shaped=new Map();
function variantData(id){
 if(shaped.has(id))return shaped.get(id);
 const spec=DATA.variants.find(v=>v.id===id),v=DATA.vertices.slice(),rig=JSON.parse(JSON.stringify(DATA.rig));
 function point(p,rump){return p.map((x,i)=>spec.size*(rump?(i===2?.4+(x-.4)*spec.rump[i]:x*spec.rump[i]):x));}
 for(let j=0;j<v.length;j+=8){const rump=DATA.rig.groups[Math.floor(j/288)].startsWith('rump');const p=point(v.slice(j,j+3),rump);v[j]=p[0];v[j+1]=p[1];v[j+2]=p[2];}
 for(const b of rig.bones)b.pivot=point(b.pivot,b.name.startsWith('rump'));
 for(const clip of Object.values(rig.clips))for(const [name,chs] of Object.entries(clip.tracks))for(const [t,p] of Object.entries(chs.position||{}))chs.position[t]=p.map((x,i)=>x*spec.size*(name.startsWith('rump')?spec.rump[i]:1));
 const result={vertices:v,rig,spec};shaped.set(id,result);return result;
}
function fleePose(id,t,angle=2.3){
 const model=variantData(id),run=Math.max(0,t-1),q=Math.max(0,Math.min(1,(t-.6)/.4)),yaw=angle*q*q*(3-2*q);
 const v=poseVertices(model.vertices,model.rig,run*1.8,true,false,'waddle');
 const c=Math.cos(yaw),s=Math.sin(yaw),hop=(t<.6?.3*Math.sin(Math.PI*t/.6):.28*Math.abs(Math.sin(run*12)))*model.spec.size;
 for(let j=0;j<v.length;j+=8){const x=v[j],z=v[j+2],nx=v[j+5],nz=v[j+7];v[j]=x*c+z*s;v[j+2]=-x*s+z*c;v[j+1]+=hop;v[j+5]=nx*c+nz*s;v[j+7]=-nx*s+nz*c;}
 return {vertices:v,state:t<.6?'受惊后缩':t<1?'转身':'逃跑 · 跟随观察',spec:model.spec};
}
