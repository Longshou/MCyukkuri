const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
function sample(clip,t,poses){
 t=clip.loop?t%clip.duration:clamp(t,0,clip.duration);
 for(const track of clip.tracks){
  const keys=track.keys;let lo=0,hi=keys.length-1;
  while(hi-lo>1){const mid=(lo+hi)>>1;if(keys[mid][0]<=t)lo=mid;else hi=mid;}
  const a=keys[lo],b=keys[Math.min(lo+1,keys.length-1)],q=a[0]===b[0]?0:clamp((t-a[0])/(b[0]-a[0]),0,1);
  const off={position:0,rotation:3,scale:6}[track.channel];
  for(let i=0;i<3;i++)poses[track.bone][off+i]=a[i+1]+(b[i+1]-a[i+1])*q;
 }
}
function posed(model,action,t,overrides=null){
 const poses=model.bones.map(()=>[0,0,0,0,0,0,1,1,1]),world=[];
 sample(model.clips.idle,0,poses);sample(model.clips[action],t,poses);
 if(overrides)for(let i=0;i<model.bones.length;i++){
  const name=model.bones[i].name,base=name.split('_deform_')[0],channels=overrides[base];
  if(channels)for(const [channel,value]of Object.entries(channels)){const off={position:0,rotation:3,scale:6}[channel];for(let k=0;k<3;k++)poses[i][off+k]=value[k];}
 }
 for(let i=0;i<model.bones.length;i++){
  const p=model.bones[i].pivot,v=poses[i],[x,y,z]=v.slice(3,6).map(a=>a*Math.PI/180);
  const cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z),[a,b,d]=v.slice(6);
  let m=[cz*cy*a,(cz*sy*sx-sz*cx)*b,(cz*sy*cx+sz*sx)*d,0,sz*cy*a,(sz*sy*sx+cz*cx)*b,(sz*sy*cx-cz*sx)*d,0,-sy*a,cy*sx*b,cy*cx*d,0];
  for(let r=0;r<3;r++)m[r*4+3]=p[r]+v[r]-m[r*4]*p[0]-m[r*4+1]*p[1]-m[r*4+2]*p[2];
  const parent=model.bones[i].parent;
  if(parent>=0){const w=world[parent],n=[];for(let r=0;r<3;r++)for(let k=0;k<4;k++)n[r*4+k]=w[r*4]*m[k]+w[r*4+1]*m[k+4]+w[r*4+2]*m[k+8]+(k===3?w[r*4+3]:0);m=n;}
  world.push(m);
 }
 const out=[],src=model.vertices,surface=[];
 for(const part of model.parts){
  if(part.anatomy&&!action.startsWith('anatomy_'+part.anatomy+'_')){
   const channels=overrides&&overrides['anatomy_'+part.anatomy];
   if(!channels||!Object.values(channels).flat().some(v=>Math.abs(v)>1e-9))continue;
  }
  for(let first=part.start;first<part.start+part.count;first+=3){
   const tri=[];
   for(let i=first;i<first+3;i++){
    const v=src.slice(i*8,i*8+8),[a,b,q]=model.weights[i],ma=world[a],mb=world[b];
    const point=[];for(let r=0;r<3;r++){const off=r*4;const transform=m=>m[off]*v[0]+m[off+1]*v[1]+m[off+2]*v[2]+m[off+3];point[r]=transform(ma)*(1-q)+transform(mb)*q;}
    tri.push([...point,v[3],v[4],0,0,0]);
   }
   const a=tri[1].slice(0,3).map((v,i)=>v-tri[0][i]),b=tri[2].slice(0,3).map((v,i)=>v-tri[0][i]);
   const n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],length=Math.hypot(...n);
   if(length<1e-8)continue;
   for(let corner=0;corner<3;corner++){
    const v=tri[corner];v.splice(5,3,...n.map(x=>x/length));
    if(part.name==='house_body_surface')surface.push([out.length,first+corner]);
    out.push(...v);
   }
  }
 }
 smoothHouseSurface(model,out,surface);
 return out;
}
