function travelOffset(rig,time){const duration=rig.clips?.waddle.duration||rig.duration;const cycles=(time%(duration*2.5))/duration;return 4-rig.stride_units*(cycles-Math.sin(4*Math.PI*cycles)/(8*Math.PI));}
const compiledMotion=new WeakMap();
function compileMotion(rig){
 let c=compiledMotion.get(rig);if(c)return c;
 const ids=Object.fromEntries(rig.bones.map((b,i)=>[b.name,i]));
 c={parents:rig.bones.map(b=>b.parent==null?-1:ids[b.parent]),groups:rig.groups.map(n=>ids[n]),clips:{}};
 let offset=0;c.ranges=rig.groups.map((_,i)=>{const start=offset;offset+=(rig.element_vertex_counts?.[i]??36)*8;return [start,offset];});
 for(const [name,clip] of Object.entries(rig.clips||{stretch:rig}))c.clips[name]=rig.bones.map(b=>Object.entries(clip.tracks[b.name]||{}).map(([ch,keys])=>({ch,keys:Object.entries(keys).map(([t,v])=>[Number(t),v]).sort((a,b)=>a[0]-b[0])})));
 compiledMotion.set(rig,c);return c;
}
function poseVertices(vertices,rig,time,enabled=true,travel=false,clipName='stretch'){
 const clip=rig.clips?.[clipName]||rig,c=compileMotion(rig),channels=c.clips[clipName]||c.clips.stretch,t=clip.loop?((time%clip.duration)+clip.duration)%clip.duration:Math.max(0,Math.min(time,clip.duration)),world=[],normal=[];
 for(let i=0;i<rig.bones.length;i++){
  let position=[0,0,0],rotation=[0,0,0],scale=[1,1,1];
  if(enabled)for(const {ch,keys} of channels[i]){
   let lo=0,hi=keys.length-1;while(hi-lo>1){const mid=(lo+hi)>>1;if(keys[mid][0]<=t)lo=mid;else hi=mid;}
   const a=keys[lo],b=keys[Math.min(lo+1,keys.length-1)],q=b[0]===a[0]?0:Math.max(0,Math.min(1,(t-a[0])/(b[0]-a[0]))),v=a[1].map((n,k)=>n+(b[1][k]-n)*q);
   if(ch==='position')position=v;else if(ch==='rotation')rotation=v;else if(ch==='scale')scale=v;
  }
  const override=rig.boneOverrides?.[rig.bones[i].name]||rig.boneOverrides?.[rig.bones[i].name.split('_deform_')[0]];
  if(override?.position)position=override.position;if(override?.rotation)rotation=override.rotation;if(override?.scale)scale=override.scale;
  const [x,y,z]=rotation.map(a=>a*Math.PI/180),cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z),[a,b,d]=scale,p=rig.bones[i].pivot;
  let m=[cz*cy*a,(cz*sy*sx-sz*cx)*b,(cz*sy*cx+sz*sx)*d,0,sz*cy*a,(sz*sy*sx+cz*cx)*b,(sz*sy*cx-cz*sx)*d,0,-sy*a,cy*sx*b,cy*cx*d,0];
  for(let row=0;row<3;row++)m[row*4+3]=p[row]+position[row]-m[row*4]*p[0]-m[row*4+1]*p[1]-m[row*4+2]*p[2];
  if(c.parents[i]>=0){const w=world[c.parents[i]],n=new Array(12);for(let r=0;r<3;r++)for(let k=0;k<4;k++)n[r*4+k]=w[r*4]*m[k]+w[r*4+1]*m[k+4]+w[r*4+2]*m[k+8]+(k===3?w[r*4+3]:0);m=n;}
  world.push(m);
  // Cofactors give the inverse-transpose, up to positive determinant removed by normalization.
  normal.push([m[5]*m[10]-m[6]*m[9],m[6]*m[8]-m[4]*m[10],m[4]*m[9]-m[5]*m[8],m[2]*m[9]-m[1]*m[10],m[0]*m[10]-m[2]*m[8],m[1]*m[8]-m[0]*m[9],m[1]*m[6]-m[2]*m[5],m[2]*m[4]-m[0]*m[6],m[0]*m[5]-m[1]*m[4]]);
 }
 const out=vertices.slice(),offset=travel&&clipName==='waddle'?travelOffset(rig,time):0;
 if(rig.vertex_weights){
  for(let i=0;i<vertices.length;i+=8){
   const [a,b,q]=rig.vertex_weights[i/8],ma=world[a],mb=world[b];
   for(let k=0;k<3;k++){const r=k*4,f=m=>m[r]*vertices[i]+m[r+1]*vertices[i+1]+m[r+2]*vertices[i+2]+m[r+3];out[i+k]=f(ma)*(1-q)+f(mb)*q+(k===2?offset:0);}
  }
  for(let i=0;i<out.length;i+=24){
   const a=[0,1,2].map(k=>out[i+8+k]-out[i+k]),b=[0,1,2].map(k=>out[i+16+k]-out[i+k]);
   const n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],l=Math.hypot(...n);
   for(let v=0;v<3;v++)for(let k=0;k<3;k++)out[i+v*8+5+k]=l>1e-10?n[k]/l:0;
  }
  return out;
 }
 for(let cube=0;cube<c.groups.length;cube++){const m=world[c.groups[cube]],n=normal[c.groups[cube]];for(let i=c.ranges[cube][0];i<c.ranges[cube][1];i+=8){const x=vertices[i],y=vertices[i+1],z=vertices[i+2],nx=vertices[i+5],ny=vertices[i+6],nz=vertices[i+7];out[i]=m[0]*x+m[1]*y+m[2]*z+m[3];out[i+1]=m[4]*x+m[5]*y+m[6]*z+m[7];out[i+2]=m[8]*x+m[9]*y+m[10]*z+m[11]+offset;const ax=n[0]*nx+n[1]*ny+n[2]*nz,ay=n[3]*nx+n[4]*ny+n[5]*nz,az=n[6]*nx+n[7]*ny+n[8]*nz,l=Math.hypot(ax,ay,az);out[i+5]=ax/l;out[i+6]=ay/l;out[i+7]=az/l;}}
 return out;
}
if(typeof module!=='undefined')module.exports={poseVertices};
