/* Pose current runtime meshes using the existing preview's bone/weight convention.
   Pure math: shared by the interactive viewer and command-line verification. */
(function(root){
const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
const smooth=x=>{x=clamp(x,0,1);return x*x*(3-2*x);};
/* Inflate, hold and release; the optional tremor only modulates the held phase. */
function puffAmount(clip,t){const e=clip.puff_envelope;if(!e)return 0;t=clip.loop?t%clip.duration:clamp(t,0,clip.duration);const u=(t-e[0])/(e[1]-e[0]);
 const base=smooth(u/e[2])*smooth((1-u)/e[2]);if(base<=0||e.length<5)return base;
 const hold=smooth((u-e[2])/e[2])*smooth((1-e[2]-u)/e[2]);return base*(1-e[3]*hold*(.5-.5*Math.cos(2*Math.PI*e[4]*(t-e[0]))));}
function smoothSurface(out,part,groups,amount=1){
 const sums=Array.from({length:groups.count},()=>[0,0,0]);
 for(let i=0;i<part.count;i+=3){const k=(part.start+i)*8;
  const ax=out[k+8]-out[k],ay=out[k+9]-out[k+1],az=out[k+10]-out[k+2],bx=out[k+16]-out[k],by=out[k+17]-out[k+1],bz=out[k+18]-out[k+2];
  const n=[ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx];for(let j=0;j<3;j++)for(let c=0;c<3;c++)sums[groups.ids[i+j]][c]+=n[c];
 }
 for(let i=0;i<part.count;i++){const n=sums[groups.ids[i]],length=Math.hypot(...n),k=(part.start+i)*8;if(length>1e-8){
  const blended=n.map((v,c)=>out[k+5+c]*(1-amount)+v/length*amount),size=Math.hypot(...blended);
  if(size>1e-8)for(let c=0;c<3;c++)out[k+5+c]=blended[c]/size;}}
}
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
function transform(m,x,y,z){return [m[0]*x+m[1]*y+m[2]*z+m[3],m[4]*x+m[5]*y+m[6]*z+m[7],m[8]*x+m[9]*y+m[10]*z+m[11]];}
function inverse(m){
 const [a,b,c,,d,e,f,,g,h,i]=m,det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
 const n=[(e*i-f*h)/det,(c*h-b*i)/det,(b*f-c*e)/det,0,(f*g-d*i)/det,(a*i-c*g)/det,(c*d-a*f)/det,0,(d*h-e*g)/det,(b*g-a*h)/det,(a*e-b*d)/det,0];
 for(let r=0;r<3;r++)n[r*4+3]=-n[r*4]*m[3]-n[r*4+1]*m[7]-n[r*4+2]*m[11];return n;
}
function multiply(a,b){const n=[];for(let r=0;r<3;r++)for(let k=0;k<4;k++)n[r*4+k]=a[r*4]*b[k]+a[r*4+1]*b[k+4]+a[r*4+2]*b[k+8]+(k===3?a[r*4+3]:0);return n;}
/* Puff meshes store shared corners once; expand to the runtime's triangle list. */
function expandPuff(part){
 const vertices=[],offsets=[],indices=part.indices||Array.from({length:part.vertices.length/8},(_,i)=>i);
 if(part.offsets&&part.offsets.length*8!==part.vertices.length*3)throw Error('Invalid puff offsets');
 for(const index of indices){if(!Number.isInteger(index)||index<0||index*8+8>part.vertices.length)throw Error('Invalid puff index');
  for(let k=0;k<8;k++)vertices.push(part.vertices[index*8+k]);if(part.offsets)offsets.push(part.offsets[index*3],part.offsets[index*3+1],part.offsets[index*3+2]);}
 return {...part,vertices,offsets};}
/* Placement fit: replacement meshes overwrite their part in place or are appended with the part
   redirected; flat normals are rebuilt. Applied before motion revisions, as YukkuriModel does. */
function spliceFit(model,fit){
 if(!fit)return model;
 const vertices=model.vertices.slice(),weights=model.weights.map(w=>w.slice()),parts=model.parts.map(p=>({...p})),bones=model.bones.map(b=>({...b}));
 for(const e of fit.parts){const part=parts[e.part_index];if(!part||part.name!==e.name)throw Error('Placement fit part mismatch');
  const count=e.indices.length;if(count%3||(e.inplace&&count!==part.count))throw Error('Invalid placement fit mesh');
  const start=e.inplace?part.start:vertices.length/8;
  for(let t=0;t<count;t+=3){const c=[0,1,2].map(j=>{const k=e.indices[t+j];if(!Number.isInteger(k)||k<0||k*5+5>e.vertices.length)throw Error('Invalid placement fit index');return k;});
   const p=c.map(k=>e.vertices.slice(k*5,k*5+3)),a=p[1].map((v,i)=>v-p[0][i]),b=p[2].map((v,i)=>v-p[0][i]);
   let n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];const l=Math.hypot(...n);n=l>1e-12?n.map(v=>v/l):[0,0,-1];
   for(let j=0;j<3;j++){const k=c[j],o=(start+t+j)*8,row=[...e.vertices.slice(k*5,k*5+5),...n],w=e.weights.slice(k*3,k*3+3);
    for(let i=0;i<8;i++)vertices[o+i]=row[i];weights[start+t+j]=w;}}
  if(!e.inplace){part.start=start;part.count=count;}}
 for(const b of fit.bone_pivots){if(!bones[b.index]||bones[b.index].name!==b.name)throw Error('Placement fit bone mismatch');bones[b.index].pivot=b.pivot.slice();}
 return {...model,vertices,weights,parts,bones};
}
class RuntimePose{
 constructor(model,scale=1,revision=null,fit=null){
  model=spliceFit(model,fit);
  const extra=[];this.puffAttachments=new Map();
  const append=(raw,index)=>{const s=expandPuff(raw),start=model.vertices.length/8+extra.reduce((n,e)=>n+e.vertices.length/8,0),count=s.vertices.length/8;
   if(count%3)throw Error('Invalid puff mesh');extra.push({vertices:s.vertices,weights:Array.from({length:count},()=>[s.bone,s.bone,0]),offsets:s.offsets});
   return {...model.parts[index],feature_bone:undefined,start,count};};
  let surface=null;
  if(revision?.puff_surface)surface={...append(revision.puff_surface,revision.puff_surface.part_index),part_index:revision.puff_surface.part_index};
  for(const raw of revision?.puff_attachments||[])this.puffAttachments.set(raw.part_index,append(raw,raw.part_index));
  if(extra.length)model={...model,vertices:model.vertices.concat(...extra.map(e=>e.vertices)),weights:model.weights.concat(...extra.map(e=>e.weights))};
  if(revision?.expression_patches)model={...model,expression_patches:{...model.expression_patches,...revision.expression_patches}};
  this.model=model;this.scale=scale;this.clips={...model.clips,...model.behavior_clips};this.puffSurface=surface;
  // Runtime contact correction targets the authored `root` bone, which may
  // itself sit below a world-motion parent. Fall back only for older sources.
  this.rootBone=model.bones.findIndex(b=>b.name==='root');
  if(this.rootBone<0)this.rootBone=model.bones.findIndex(b=>b.parent<0);
  if(this.rootBone<0)throw Error('Model has no root bone');
  if(revision){if(JSON.stringify(revision.bone_names)!==JSON.stringify(model.bones.map(b=>b.name)))throw Error('动作骨架与模型不匹配');Object.assign(this.clips,revision.clips);}
  // One morph target: every displaced vertex owns a full rest-space offset.
  this.puffOffsets=new Float64Array(model.vertices.length/8*3);let cursor=(model.vertices.length-extra.reduce((n,e)=>n+e.vertices.length,0))/8;
  for(const e of extra){if(e.offsets.length)this.puffOffsets.set(e.offsets,cursor*3);cursor+=e.vertices.length/8;}
  for(const raw of revision?.puff_offsets||[]){const part=model.parts[raw.part_index];if(!part||raw.offsets.length!==part.count*3)throw Error('Invalid puff offsets');this.puffOffsets.set(raw.offsets,part.start*3);}
  this.expressionAliases=revision?.expression_aliases||{};
  if(surface){const map=new Map(),ids=[];for(let i=surface.start;i<surface.start+surface.count;i++){const key=model.vertices.slice(i*8,i*8+3).map(v=>Math.round(v*1e5)).join(',');if(!map.has(key))map.set(key,map.size);ids.push(map.get(key));}this.puffGroups={ids,count:map.size};}
  this.output=new Float64Array(model.vertices.length);this.expression('neutral');}
 expressionFor(action,t,fallback='neutral'){const clip=this.clips[action];t=clip.loop?t%clip.duration:clamp(t,0,clip.duration);return (clip.expression_spans||[]).find(s=>t>=s[0]&&t<s[1])?.[2]||fallback;}
 expression(name){
  const patches=this.model.expression_patches||{};
  // A revision may supply its own named patch; otherwise borrow an alias's geometry.
  if(!patches[name])name=this.expressionAliases[name]||name;
  this.src=Float64Array.from(this.model.vertices);
  for(const p of patches[name]||[]){const part=this.model.parts[p.index];if(!part||p.vertices.length!==part.count*8)throw Error('Invalid expression part');this.src.set(p.vertices,part.start*8);}
 }
 frame(action,t,rootAdjustment=null){
  const model=this.model,poses=model.bones.map(()=>[0,0,0,0,0,0,1,1,1]),world=[];
  sample(this.clips.idle,0,poses);sample(this.clips[action],t,poses);
  if(rootAdjustment){
   const adjustment=poses[this.rootBone],translation=rootAdjustment.translation||[0,0,0];
   if(!Array.isArray(translation)||translation.length!==3)throw Error('Invalid root translation');
   for(let i=0;i<3;i++)adjustment[i]+=translation[i];
   adjustment[3]+=rootAdjustment.pitch||0;
  }
  for(let i=0;i<model.bones.length;i++){
   const p=model.bones[i].pivot,v=poses[i],[x,y,z]=v.slice(3,6).map(a=>a*Math.PI/180);
   const cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z),[a,b,d]=v.slice(6);
   let m=[cz*cy*a,(cz*sy*sx-sz*cx)*b,(cz*sy*cx+sz*sx)*d,0,sz*cy*a,(sz*sy*sx+cz*cx)*b,(sz*sy*cx-cz*sx)*d,0,-sy*a,cy*sx*b,cy*cx*d,0];
   for(let r=0;r<3;r++)m[r*4+3]=p[r]+v[r]-m[r*4]*p[0]-m[r*4+1]*p[1]-m[r*4+2]*p[2];
   const parent=model.bones[i].parent;if(parent>=0)m=multiply(world[parent],m);world.push(m);
  }
  this.world=world;
  const out=this.output,src=this.src,offsets=this.puffOffsets,puff=puffAmount(this.clips[action],t);out.fill(0);
  for(let pi=0;pi<model.parts.length;pi++){
   const part=puff>0?(this.puffSurface?.part_index===pi?this.puffSurface:this.puffAttachments.get(pi)||model.parts[pi]):model.parts[pi];
   if(part.anatomy&&!action.startsWith('anatomy_'+part.anatomy+'_'))continue;
   let feature=null;
   if(part.feature_bone!==undefined){const b=part.feature_bone,p=model.bones[b].parent;feature=p>=0?multiply(world[b],inverse(world[p])):world[b];}
   for(let i=part.start;i<part.start+part.count;i++){
    const k=i*8,[a,b,q]=model.weights[i],ma=world[a],mb=world[b];
    const vx=src[k]+offsets[i*3]*puff,vy=src[k+1]+offsets[i*3+1]*puff,vz=src[k+2]+offsets[i*3+2]*puff;
    let x=0,y=0,z=0;
    for(let r=0;r<3;r++){const j=r*4;const value=(ma[j]*vx+ma[j+1]*vy+ma[j+2]*vz+ma[j+3])*(1-q)+(mb[j]*vx+mb[j+1]*vy+mb[j+2]*vz+mb[j+3])*q;if(r===0)x=value;else if(r===1)y=value;else z=value;}
    if(feature)[x,y,z]=transform(feature,x,y,z);
    out[k]=x*this.scale;out[k+1]=y*this.scale;out[k+2]=z*this.scale;out[k+3]=src[k+3];out[k+4]=src[k+4];
   }
   for(let first=part.start*8;first<(part.start+part.count)*8;first+=24){
    if(model.house&&part.name.startsWith('face_')&&src[first+7]>=-.15){out.fill(0,first,first+24);continue;}
    const ax=out[first+8]-out[first],ay=out[first+9]-out[first+1],az=out[first+10]-out[first+2];
    const bx=out[first+16]-out[first],by=out[first+17]-out[first+1],bz=out[first+18]-out[first+2];
    const nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,len=Math.hypot(nx,ny,nz);
    if(len<1e-8)continue;
    for(let j=0;j<3;j++){out[first+j*8+5]=nx/len;out[first+j*8+6]=ny/len;out[first+j*8+7]=nz/len;}
   }
   if(puff>0&&part===this.puffSurface)smoothSurface(out,part,this.puffGroups,puff);
  }return out;
 }
 rootPoint(point){
  if(!this.world)throw Error('Sample a frame before reading its root point');
  return transform(this.world[this.rootBone],point[0],point[1],point[2]);
 }
}
root.RuntimePose=RuntimePose;root.spliceFit=spliceFit;if(typeof module!=='undefined')module.exports={RuntimePose,puffAmount,spliceFit};
})(typeof window==='undefined'?globalThis:window);
