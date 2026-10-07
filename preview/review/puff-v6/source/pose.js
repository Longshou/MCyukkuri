/* Pose current runtime meshes using the existing preview's bone/weight convention.
   Pure math: shared by the interactive viewer and command-line verification. */
(function(root){
const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
const smooth=x=>{x=clamp(x,0,1);return x*x*(3-2*x);};
function puffAmount(clip,t){const e=clip.puff_envelope;if(!e)return 0;t=clip.loop?t%clip.duration:clamp(t,0,clip.duration);const u=(t-e[0])/(e[1]-e[0]);return smooth(u/e[2])*smooth((1-u)/e[2]);}
function cheekDelta(c,x,y,z){
 if(!c||z>=c[5])return [0,0];
 if(c.length===11){
  const yy=(y-c[1])/c[3];if(Math.abs(yy)>=1)return [0,0];
  const yz=(c[10]===2?(1-yy*yy)**2:Math.sqrt(1-yy*yy))*smooth((c[5]-z)/(c[5]-c[4]));
  const dx=Math.sign(x)*c[6]*smooth((Math.abs(x)-c[0])/c[2])*yz;
  // Compose two monotone maps; the depth pass uses the expanded x coordinate.
  const q=smooth((Math.abs(x+dx)-c[0])/c[2])*yz*smooth((c[9]-y)/(c[9]-c[8]));
  return [dx,-c[7]*q];
 }
 const xx=(Math.abs(x)-c[0])/c[2],yy=(y-c[1])/c[3],r=xx*xx+yy*yy;
 if(r>=1)return [0,0];const eyeMask=c.length>8?smooth((c[9]-y)/(c[9]-c[8])):1;
 const q=(1-r)**2*smooth((c[5]-z)/(c[5]-c[4]))*eyeMask;return [Math.sign(x)*c[6]*q,-c[7]*q];
}
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
function expandPuff(part){if(!part.indices)return part;
 const vertices=[],offsets=[];for(const index of part.indices){if(!Number.isInteger(index)||index<0||index*8+8>part.vertices.length)throw Error('Invalid puff index');
  for(let k=0;k<8;k++)vertices.push(part.vertices[index*8+k]);if(part.offsets)offsets.push(part.offsets[index*2],part.offsets[index*2+1]);}
 return {...part,vertices,offsets};}
class RuntimePose{
 constructor(model,scale=1,revision=null){
  if(revision?.puff_surface&&!model.puff_surface){const s=expandPuff(revision.puff_surface),start=model.vertices.length/8,count=s.vertices.length/8;
   model={...model,vertices:model.vertices.concat(s.vertices),weights:model.weights.concat(Array.from({length:count},()=>[s.bone,s.bone,0])),puff_surface:{...model.parts[s.part_index],start,count,part_index:s.part_index}};}
  this.puffAttachments=new Map();
  for(const raw of revision?.puff_attachments||[]){const s=expandPuff(raw),start=model.vertices.length/8,count=s.vertices.length/8;
   model={...model,vertices:model.vertices.concat(s.vertices),weights:model.weights.concat(Array.from({length:count},()=>[s.bone,s.bone,0]))};
   this.puffAttachments.set(s.part_index,{...model.parts[s.part_index],feature_bone:undefined,start,count,offsets:s.offsets});}
  this.model=model;this.scale=scale;this.clips={...model.clips,...model.behavior_clips};
  if(revision){if(JSON.stringify(revision.bone_names)!==JSON.stringify(model.bones.map(b=>b.name)))throw Error('动作骨架与模型不匹配');Object.assign(this.clips,revision.clips);}
  this.cheeks=revision?.cheek_puff;
  this.expressionAliases=revision?.expression_aliases||{};
  if(model.puff_surface){const p=model.puff_surface,map=new Map(),ids=[];for(let i=p.start;i<p.start+p.count;i++){const key=model.vertices.slice(i*8,i*8+3).map(v=>Math.round(v*1e5)).join(',');if(!map.has(key))map.set(key,map.size);ids.push(map.get(key));}this.puffGroups={ids,count:map.size};}
  this.output=new Float64Array(model.vertices.length);this.expression('neutral');}
 expressionFor(action,t,fallback='neutral'){const clip=this.clips[action];t=clip.loop?t%clip.duration:clamp(t,0,clip.duration);return (clip.expression_spans||[]).find(s=>t>=s[0]&&t<s[1])?.[2]||fallback;}
 expression(name){
  name=this.expressionAliases[name]||name;
  this.src=Float64Array.from(this.model.vertices);
  for(const p of this.model.expression_patches?.[name]||[]){const part=this.model.parts[p.index];if(!part||p.vertices.length!==part.count*8)throw Error('Invalid expression part');this.src.set(p.vertices,part.start*8);}
  this.cheekDeltas=new Float64Array(this.model.vertices.length/4);
  // Only skin and cheek pigment participate. Eyes, brows, mouth and accessories
  // retain their full rest geometry, including UVs, throughout inflation.
  if(this.cheeks)for(const part of [...this.model.parts,...(this.model.puff_surface?[this.model.puff_surface]:[])]){
   if(!part.name.includes('body_surface'))continue;
   for(let k=part.start*8;k<(part.start+part.count)*8;k+=8){const d=cheekDelta(this.cheeks,this.src[k],this.src[k+1],this.src[k+2]);this.cheekDeltas[k/4]=d[0];this.cheekDeltas[k/4+1]=d[1];}}
  if(this.puffAttachments.size){
   for(const part of this.puffAttachments.values())this.cheekDeltas.set(part.offsets,part.start*2);
  }else if(this.cheeks){
   // A blush decal is much coarser than the skin. Vertex-wise projection can
   // shear it and cut its middle into the cheek. Preserve its shape and move
   // it forward as one piece by the maximum skin displacement.
   let forward=0;for(let k=1;k<this.cheekDeltas.length;k+=2)forward=Math.min(forward,this.cheekDeltas[k]);
   for(const part of this.model.parts){if(!part.name.startsWith('face_blush_'))continue;
    for(let k=part.start*8;k<(part.start+part.count)*8;k+=8)this.cheekDeltas[k/4+1]=forward;}
  }
 }
 frame(action,t){
  const model=this.model,poses=model.bones.map(()=>[0,0,0,0,0,0,1,1,1]),world=[];
  sample(this.clips.idle,0,poses);sample(this.clips[action],t,poses);
  for(let i=0;i<model.bones.length;i++){
   const p=model.bones[i].pivot,v=poses[i],[x,y,z]=v.slice(3,6).map(a=>a*Math.PI/180);
   const cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z),[a,b,d]=v.slice(6);
   let m=[cz*cy*a,(cz*sy*sx-sz*cx)*b,(cz*sy*cx+sz*sx)*d,0,sz*cy*a,(sz*sy*sx+cz*cx)*b,(sz*sy*cx-cz*sx)*d,0,-sy*a,cy*sx*b,cy*cx*d,0];
   for(let r=0;r<3;r++)m[r*4+3]=p[r]+v[r]-m[r*4]*p[0]-m[r*4+1]*p[1]-m[r*4+2]*p[2];
   const parent=model.bones[i].parent;if(parent>=0)m=multiply(world[parent],m);world.push(m);
  }
  const out=this.output,src=this.src,puff=puffAmount(this.clips[action],t);out.fill(0);
  for(let pi=0;pi<model.parts.length;pi++){
   const part=puff>0?(model.puff_surface?.part_index===pi?model.puff_surface:this.puffAttachments.get(pi)||model.parts[pi]):model.parts[pi];
   if(part.anatomy&&!action.startsWith('anatomy_'+part.anatomy+'_'))continue;
   let feature=null;
   if(part.feature_bone!==undefined){const b=part.feature_bone,p=model.bones[b].parent;feature=p>=0?multiply(world[b],inverse(world[p])):world[b];}
   for(let i=part.start;i<part.start+part.count;i++){
    const k=i*8,[a,b,q]=model.weights[i],ma=world[a],mb=world[b];
    const vx=src[k]+this.cheekDeltas[i*2]*puff,vy=src[k+1],vz=src[k+2]+this.cheekDeltas[i*2+1]*puff;
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
   if(puff>0&&part===model.puff_surface)smoothSurface(out,part,this.puffGroups,puff);
  }return out;
 }
}
root.RuntimePose=RuntimePose;if(typeof module!=='undefined')module.exports={RuntimePose,cheekDelta,puffAmount};
})(typeof window==='undefined'?globalThis:window);
