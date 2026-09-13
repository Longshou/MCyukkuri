function travelOffset(rig,time){const cycles=(time%(rig.duration*2.5))/rig.duration;return 4-rig.stride_units*(cycles-Math.sin(4*Math.PI*cycles)/(8*Math.PI));}
// Shared by the browser preview and Node verification: the exported rig is the source of motion.
function poseVertices(vertices,rig,time,enabled=true,travel=false){
 const out=vertices.slice(),t=((time%rig.duration)+rig.duration)%rig.duration,poses={};
 for(const bone of rig.bones){
  const channels=rig.tracks[bone.name]||{},p={bone,position:[0,0,0],rotation:[0,0,0]};
  if(enabled)for(const [channel,keys] of Object.entries(channels)){
   const entries=Object.entries(keys).sort((a,b)=>Number(a[0])-Number(b[0])),times=entries.map(e=>Number(e[0]));let j=0;
   while(j<times.length-2&&times[j+1]<t)j++;
   const a=times[j],b=times[j+1],q=(t-a)/(b-a);
   p[channel]=entries[j][1].map((v,k)=>v+(entries[j+1][1][k]-v)*q);
  }
  p.rotation=p.rotation.map(v=>v*Math.PI/180);poses[bone.name]=p;
 }
 const transform=(v,name,normal)=>{
  while(name){const p=poses[name],pivot=normal?[0,0,0]:p.bone.pivot;
   let [x,y,z]=v.map((q,k)=>q-pivot[k]);
   for(let axis=0;axis<3;axis++){const a=p.rotation[axis],c=Math.cos(a),s=Math.sin(a);
    if(axis===0)[y,z]=[y*c-z*s,y*s+z*c];
    if(axis===1)[x,z]=[x*c+z*s,-x*s+z*c];
    if(axis===2)[x,y]=[x*c-y*s,x*s+y*c];
   }
   v=[x,y,z].map((q,k)=>q+pivot[k]+(normal?0:p.position[k]));name=p.bone.parent;
  }
  if(travel&&!normal)v[2]+=travelOffset(rig,time);
  return v;
 };
 for(let i=0;i<vertices.length;i+=8){const group=rig.groups[Math.floor(i/288)];
  const p=transform(vertices.slice(i,i+3),group,false),n=transform(vertices.slice(i+5,i+8),group,true);
  for(let k=0;k<3;k++){out[i+k]=p[k];out[i+5+k]=n[k];}
 }
 return out;
}
if(typeof module!=='undefined')module.exports={poseVertices};
