/* Shared authoring/preview geometry. Positions bind to the selected body's skin,
 * including its actual surface and skinning weights; hair remains an occluder. */
(function(root){
'use strict';
const add=(a,b)=>a.map((x,i)=>x+b[i]),sub=(a,b)=>a.map((x,i)=>x-b[i]),mul=(a,s)=>a.map(x=>x*s),dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0);
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],norm=a=>mul(a,1/(Math.hypot(...a)||1));
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x)),mix=(a,b,t)=>a.map((x,i)=>x*(1-t)+b[i]*t);
const sides={front:{label:'正面',n:[0,0,-1],u:[1,0,0],v:[0,1,0]},right:{label:'右侧',n:[1,0,0],u:[0,0,1],v:[0,1,0]},back:{label:'背面',n:[0,0,1],u:[-1,0,0],v:[0,1,0]},left:{label:'左侧',n:[-1,0,0],u:[0,0,-1],v:[0,1,0]},top:{label:'顶部',n:[0,1,0],u:[1,0,0],v:[0,0,1]},bottom:{label:'底部',n:[0,-1,0],u:[1,0,0],v:[0,0,-1]}};
const kinds={scratch:'擦伤',tear:'裂口',dent:'凹陷',scorch:'焦痕'};
const skinName=n=>/^(soft_head_slice_|upper_rear_slice_|left_cheek_slice_|right_cheek_slice_|rump_core_slice_|rump_round_end_slice_|crown$)/.test(n);
const cache=new WeakMap();
function weights(raw){const [a,b,t]=raw;return a===b?[[a,1]]:[[a,1-t],[b,t]];}
function blendWeights(corners,coeff){const m=new Map();corners.forEach((v,i)=>v.w.forEach(([b,w])=>m.set(b,(m.get(b)||0)+w*coeff[i])));return [...m].filter(([,w])=>w>1e-8);}
function interpolate(c,u,v){const f=[(1-u)*(1-v),u*(1-v),(1-u)*v,u*v];return {p:c[0].p.map((_,k)=>c.reduce((s,x,i)=>s+x.p[k]*f[i],0)),uv:c[0].uv.map((_,k)=>c.reduce((s,x,i)=>s+x.uv[k]*f[i],0)),w:blendWeights(c,f),skin:c.every(v=>v.skin)};}
function facePoint(f,a,b){return interpolate(f.c,(a-f.low[0])/(f.high[0]-f.low[0]),(b-f.low[1])/(f.high[1]-f.low[1]));}
function prepare(model){
 if(cache.has(model))return cache.get(model);
 const boxes=[],decor=[];
 for(const part of model.parts){
  if(part.anatomy||part.name.startsWith('puff_'))continue;
  const vs=[];for(let i=part.start;i<part.start+part.count;i++){const v=model.vertices.slice(i*8,i*8+8);vs.push({p:v.slice(0,3),uv:v.slice(3,5),n:v.slice(5,8),w:weights(model.weights[i])});}
  if(skinName(part.name)){vs.forEach(v=>v.skin=true);const lo=[0,1,2].map(k=>Math.min(...vs.map(v=>v.p[k]))),hi=[0,1,2].map(k=>Math.max(...vs.map(v=>v.p[k])));boxes.push({name:part.name,lo,hi,vs});}
  else decor.push({name:part.name,vs,face:part.name.startsWith('face_')});
 }
 const faces=[];
 // Split each cube face where another skin cube overlaps it, retaining only
 // the union's exposed surface. Internal slice caps cannot bridge a wound.
 for(const box of boxes)for(let axis=0;axis<3;axis++)for(const sign of [-1,1]){
  const plane=sign<0?box.lo[axis]:box.hi[axis],axes=[0,1,2].filter(k=>k!==axis),n=[0,0,0];n[axis]=sign;
  const originals=box.vs.filter(v=>Math.abs(v.p[axis]-plane)<1e-5&&dot(v.n,n)>.8);if(!originals.length)continue;
  const low=axes.map(k=>box.lo[k]),high=axes.map(k=>box.hi[k]);
  const c=[[0,0],[1,0],[0,1],[1,1]].map(([a,b])=>originals.reduce((best,v)=>Math.abs(v.p[axes[0]]-(a?high[0]:low[0]))+Math.abs(v.p[axes[1]]-(b?high[1]:low[1]))<Math.abs(best.p[axes[0]]-(a?high[0]:low[0]))+Math.abs(best.p[axes[1]]-(b?high[1]:low[1]))?v:best));
  const covers=boxes.filter(other=>other!==box&&plane+sign*.0001>other.lo[axis]&&plane+sign*.0001<other.hi[axis]&&axes.every((k,i)=>other.hi[k]>low[i]+1e-6&&other.lo[k]<high[i]-1e-6));
  const cuts=axes.map((k,i)=>[...new Set([low[i],high[i],...covers.flatMap(b=>[clamp(b.lo[k],low[i],high[i]),clamp(b.hi[k],low[i],high[i])])])].sort((a,b)=>a-b));
  const base={c,low,high,n,axis,axes,plane,name:box.name};
  for(let a=0;a<cuts[0].length-1;a++)for(let b=0;b<cuts[1].length-1;b++){
   const lo=[cuts[0][a],cuts[1][b]],hi=[cuts[0][a+1],cuts[1][b+1]],mid=lo.map((x,i)=>(x+hi[i])/2);
   if(hi[0]-lo[0]<1e-6||hi[1]-lo[1]<1e-6||covers.some(o=>axes.every((k,i)=>mid[i]>o.lo[k]-1e-6&&mid[i]<o.hi[k]+1e-6)))continue;
   faces.push({...base,low:lo,high:hi,c:[facePoint(base,lo[0],lo[1]),facePoint(base,hi[0],lo[1]),facePoint(base,lo[0],hi[1]),facePoint(base,hi[0],hi[1])]});
  }
 }
 // A continuous field of the source skin's bone influences gives coincident
 // vertices one binding. Otherwise independently bound cube slices open seams
 // after their internal caps are removed. Decals sample this same field.
 const grid=new Map(),influences=new Map(),radius=.65,cellSize=1.5;
 for(const f of faces){const lo=[0,0,0],hi=[0,0,0];lo[f.axis]=hi[f.axis]=f.plane;f.axes.forEach((k,i)=>{lo[k]=f.low[i];hi[k]=f.high[i];});const start=lo.map(x=>Math.floor((x-radius)/cellSize)),end=hi.map(x=>Math.floor((x+radius)/cellSize));
  for(let x=start[0];x<=end[0];x++)for(let y=start[1];y<=end[1];y++)for(let z=start[2];z<=end[2];z++){const key=[x,y,z].join(',');if(!grid.has(key))grid.set(key,[]);grid.get(key).push(f);}
 }
 function influence(point){const key=point.map(x=>x.toFixed(5)).join(',');if(influences.has(key))return influences.get(key);const candidates=grid.get(point.map(x=>Math.floor(x/cellSize)).join(','))||faces,near=[];
  for(const f of candidates){const a=clamp(point[f.axes[0]],f.low[0],f.high[0]),b=clamp(point[f.axes[1]],f.low[1],f.high[1]),q=point.slice();q[f.axis]=f.plane;q[f.axes[0]]=a;q[f.axes[1]]=b;const d=Math.hypot(...sub(q,point));if(d>=radius)continue;const t=d/radius;near.push({v:facePoint(f,a,b),factor:(1-t)**4*(4*t+1)});}
  if(!near.length){const f=faces.reduce((best,f)=>Math.abs(point[f.axis]-f.plane)<Math.abs(point[best.axis]-best.plane)?f:best);near.push({v:facePoint(f,clamp(point[f.axes[0]],f.low[0],f.high[0]),clamp(point[f.axes[1]],f.low[1],f.high[1])),factor:1});}
  const sum=near.reduce((s,v)=>s+v.factor,0),w=new Map();for(const {v,factor}of near)for(const [bone,weight]of v.w)w.set(bone,(w.get(bone)||0)+weight*factor/sum);const result=[...w];influences.set(key,result);return result;
 }
 const lo=[0,1,2].map(k=>Math.min(...boxes.map(b=>b.lo[k]))),hi=[0,1,2].map(k=>Math.max(...boxes.map(b=>b.hi[k]))),center=lo.map((x,i)=>(x+hi[i])/2),half=lo.map((x,i)=>(hi[i]-x)/2);
 const result={model,boxes,faces,decor,influence,center,half};cache.set(model,result);return result;
}
function ray(pre,origin,direction){let best=null;
 for(const f of pre.faces){const den=direction[f.axis];if(Math.abs(den)<1e-8||dot(f.n,direction)>=0)continue;const t=(f.plane-origin[f.axis])/den;if(t<0||(best&&t>=best.t))continue;
  const p=add(origin,mul(direction,t)),[a,b]=f.axes.map(k=>p[k]);if(a<f.low[0]-1e-6||a>f.high[0]+1e-6||b<f.low[1]-1e-6||b>f.high[1]+1e-6)continue;
  best={...facePoint(f,a,b),n:f.n,t,part:f.name};
 }return best;
}
function locate(pre,w){
 const s=sides[w.side]||sides.front,center=pre.center,ranges=[s.u,s.v].map(v=>dot(v.map(Math.abs),pre.half));
 const p=add(add(center,mul(s.u,clamp(w.u??-.68,-1,1)*ranges[0])),mul(s.v,clamp(w.v??-.58,-1,1)*ranges[1]));
 const h=ray(pre,add(p,mul(s.n,30)),mul(s.n,-1));return h?{...h,u:s.u,v:s.v,side:w.side}:null;
}
function defaults(kind='tear'){return {species:'reimu',showCover:true,scale:1,deform:'none',amount:2,action:'none',phase:0,flight:.5,wounds:[{id:'w1',kind:kinds[kind]?kind:'tear',side:'front',u:-.68,v:-.58,severity:2,size:1,quantity:0,mode:1}]};}
function matrices(model,action,phase){
 const poses=model.bones.map(()=>[0,0,0,0,0,0,1,1,1]),world=[];
 function sample(clip,t){if(!clip)return;t=clamp(t,0,clip.duration);for(const tr of clip.tracks){let a=tr.keys[0],b=tr.keys[tr.keys.length-1];for(let i=0;i<tr.keys.length-1;i++)if(t<=tr.keys[i+1][0]){a=tr.keys[i];b=tr.keys[i+1];break;}const q=a[0]===b[0]?0:clamp((t-a[0])/(b[0]-a[0]),0,1),off={position:0,rotation:3,scale:6}[tr.channel];for(let k=0;k<3;k++)poses[tr.bone][off+k]=a[k+1]*(1-q)+b[k+1]*q;}}
 sample(model.clips.idle,0);if(action!=='none')sample(model.clips[action],phase*(model.clips[action]?.duration||1));
 for(let i=0;i<model.bones.length;i++){
  const p=model.bones[i].pivot,v=poses[i],[x,y,z]=v.slice(3,6).map(x=>x*Math.PI/180),cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z),[a,b,d]=v.slice(6);
  let m=[cz*cy*a,(cz*sy*sx-sz*cx)*b,(cz*sy*cx+sz*sx)*d,0,sz*cy*a,(sz*sy*sx+cz*cx)*b,(sz*sy*cx-cz*sx)*d,0,-sy*a,cy*sx*b,cy*cx*d,0];
  for(let r=0;r<3;r++)m[r*4+3]=p[r]+v[r]-dot(m.slice(r*4,r*4+3),p);
  const parent=model.bones[i].parent;if(parent>=0){const w=world[parent],n=[];for(let r=0;r<3;r++)for(let k=0;k<4;k++)n[r*4+k]=w[r*4]*m[k]+w[r*4+1]*m[k+4]+w[r*4+2]*m[k+8]+(k===3?w[r*4+3]:0);m=n;}world.push(m);
 }return world;
}
function scene(input,recipe){
 const cfg={...defaults(),...recipe},pre=prepare(input.actors[cfg.species]),world=matrices(pre.model,cfg.action,cfg.phase),out=[],parts=[],anchors=[],picking=[];let recordSkin=false;
 const wounds=(cfg.wounds||[]).filter(w=>w.severity>0).map(w=>({...w,anchor:locate(pre,w)})).filter(w=>w.anchor);
 const factor=cfg.amount/3,scale=cfg.deform==='compression'?[1+.23*factor,1-.48*factor,1+.13*factor]:cfg.deform==='desiccation'?[1-.20*factor,1-.13*factor,1-.18*factor]:[1,1,1];
 function dent(p){let result=p.slice();for(const w of wounds){if(!['tear','dent'].includes(w.kind))continue;const a=w.anchor,d=sub(p,a.p),dist=dot(d,a.n);if(Math.abs(dist)>.16)continue;
  const radius=(w.size||1)*(.6+w.severity*.16),r=(dot(d,a.u)/(1.15*radius))**2+(dot(d,a.v)/(.85*radius))**2;if(r<1){const depth=(w.kind==='dent'?[0,.25,.60,1.0][w.severity]:.18*w.severity)*(1-r)**2;result=sub(result,mul(a.n,depth));}}
  return result;
 }
 function posed(v,rigid=false,offset=null){let p=dent(v.p);if(offset)p=add(p,offset);
  if(rigid){const root=cfg.species==='reimu'?[0,9.328,8.275]:[0,9.54,1.6351562];p=p.map((x,k)=>x+root[k]*(scale[k]-1));}else p=p.map((x,k)=>x*scale[k]);
  const result=[0,0,0];for(const [bone,w] of (v.skin?pre.influence(v.p):v.w)){const m=world[bone];for(let k=0;k<3;k++)result[k]+=w*(dot(m.slice(k*4,k*4+3),p)+m[k*4+3]);}return mul(result,cfg.scale);
 }
 const expression=wounds.length?Math.max(...wounds.map(w=>w.severity))>1?[384,0]:[256,128]:[256,0];
 function emit(a,b,c,uvs){const n=norm(cross(sub(b,a),sub(c,a)));if(Math.hypot(...cross(sub(b,a),sub(c,a)))<1e-9)return;[a,b,c].forEach((p,i)=>out.push(...p,...uvs[i],...n));}
 function triangle(vs,n,material=null,rigid=false){let [a,b,c]=vs;if(dot(cross(sub(b.p,a.p),sub(c.p,a.p)),n)<0)[b,c]=[c,b];
  const uv=material?input.materials[material]:null,points=[a,b,c].map(v=>posed(v,rigid));emit(...points,[a,b,c].map(v=>uv||[(expression[0]+v.uv[0]*128)/512,(expression[1]+v.uv[1]*128)/512]));
  if(recordSkin)picking.push({points,rest:[a.p,b.p,c.p],n});
 }
 function part(name,fn,meta={}){const start=out.length/8;fn();parts.push({name,start,count:out.length/8-start,...meta});}
 recordSkin=true;part('body_surface',()=>{for(const f of pre.faces){const nx=Math.max(1,Math.ceil((f.high[0]-f.low[0])/.40)),ny=Math.max(1,Math.ceil((f.high[1]-f.low[1])/.35));for(let x=0;x<nx;x++)for(let y=0;y<ny;y++){
  const a=interpolate(f.c,x/nx,y/ny),b=interpolate(f.c,(x+1)/nx,y/ny),c=interpolate(f.c,(x+1)/nx,(y+1)/ny),d=interpolate(f.c,x/nx,(y+1)/ny);triangle([a,b,c],f.n);triangle([a,c,d],f.n);
 }}});recordSkin=false;
 for(const decor of pre.decor){if(!cfg.showCover&&!decor.face)continue;part(decor.name,()=>{for(let i=0;i<decor.vs.length;i+=3){const src=decor.vs.slice(i,i+3),rigid=/^(bow_|hat_|brim_)/.test(decor.name);if(!decor.face){triangle(src,src[0].n,null,rigid);continue;}
  // Face glyphs are subdivided, retaining their own alpha/UV and binding.
  const steps=Math.max(1,Math.ceil(Math.max(...[sub(src[1].p,src[0].p),sub(src[2].p,src[0].p)].map(v=>Math.hypot(...v)))/.35));
  const v=(a,b)=>{const f=[1-a-b,a,b];return {p:src[0].p.map((_,k)=>src.reduce((s,x,i)=>s+x.p[k]*f[i],0)),uv:src[0].uv.map((_,k)=>src.reduce((s,x,i)=>s+x.uv[k]*f[i],0)),w:blendWeights(src,f),skin:true};};
  for(let a=0;a<steps;a++)for(let b=0;b<steps-a;b++){triangle([v(a/steps,b/steps),v((a+1)/steps,b/steps),v(a/steps,(b+1)/steps)],src[0].n);if(a+b<steps-1)triangle([v((a+1)/steps,b/steps),v((a+1)/steps,(b+1)/steps),v(a/steps,(b+1)/steps)],src[0].n);}
 }},{cover:!decor.face});}
 function patch(w,x,y,offset=.035){const a=w.anchor,o=add(add(a.p,mul(a.u,x)),mul(a.v,y)),hit=ray(pre,add(o,mul(a.n,4)),mul(a.n,-1));if(!hit||dot(hit.n,a.n)<.5||Math.abs(dot(sub(hit.p,a.p),a.n))>1.6)return null;return {base:hit,p:posed(hit,false,mul(hit.n,offset)),normal:hit.n};}
 function patchTri(w,xy,mat){const vs=xy.map(([x,y])=>patch(w,x,y,mat==='wound_edge'||mat==='dry_line'?.055:.035));if(vs.some(v=>!v))return;
  if(Math.max(...[sub(vs[1].base.p,vs[0].base.p),sub(vs[2].base.p,vs[0].base.p)].map(p=>Math.hypot(...p)))>.7)return;
  let [a,b,c]=vs;if(dot(cross(sub(b.base.p,a.base.p),sub(c.base.p,a.base.p)),w.anchor.n)<0)[b,c]=[c,b];emit(a.p,b.p,c.p,[input.materials[mat],input.materials[mat],input.materials[mat]]);
 }
 function disk(w,cx,cy,rx,ry,mat,inner=0){const rings=8,n=48;for(let r=0;r<rings;r++)for(let i=0;i<n;i++){
  const point=(t,j)=>{const angle=j*Math.PI*2/n,rough=1+.09*Math.sin(angle*7+w.id.length)+.04*Math.cos(angle*13);return [cx+rx*t*Math.cos(angle)*rough,cy+ry*t*Math.sin(angle)*rough];};
  const a=point(inner+(1-inner)*r/rings,i),b=point(inner+(1-inner)*(r+1)/rings,i),c=point(inner+(1-inner)*(r+1)/rings,i+1),d=point(inner+(1-inner)*r/rings,i+1);patchTri(w,[a,b,c],mat);patchTri(w,[a,c,d],mat);
 }}
 function line(w,path,width,mat){for(let i=0;i<path.length-1;i++){const a=path[i],b=path[i+1],len=Math.hypot(...sub(b,a)),n=[-(b[1]-a[1])/len*width/2,(b[0]-a[0])/len*width/2],steps=Math.ceil(len/.12);for(let s=0;s<steps;s++){const p=mix(a,b,s/steps),q=mix(a,b,(s+1)/steps);patchTri(w,[sub(p,n),sub(q,n),add(q,n)],mat);patchTri(w,[sub(p,n),add(q,n),add(p,n)],mat);}}}
 function ellipsoid(center,radii,material){const n=10,k=6,uv=input.materials[material];for(let j=0;j<k;j++)for(let i=0;i<n;i++){const point=(a,b)=>[center[0]+radii[0]*Math.sin(a*Math.PI/k)*Math.cos(b*Math.PI*2/n),center[1]+radii[1]*Math.cos(a*Math.PI/k),center[2]+radii[2]*Math.sin(a*Math.PI/k)*Math.sin(b*Math.PI*2/n)];const a=point(j,i),b=point(j+1,i),c=point(j+1,i+1),d=point(j,i+1);emit(a,c,b,[uv,uv,uv]);emit(a,d,c,[uv,uv,uv]);}}
 for(const w of wounds){const s=(w.size||1)*(.60+w.severity*.16);part('wound_'+w.id,()=>{
  if(w.kind==='tear'){disk(w,0,0,1.13*s,.79*s,'skin_light',.88);disk(w,0,0,1.0*s,.70*s,'wound_edge',.78);disk(w,0,0,.80*s,.55*s,'deep_filling');}
  if(w.kind==='dent'){for(const side of [-1,1])line(w,Array.from({length:12},(_,i)=>[side*s*(1.0-.12*Math.sin(i*Math.PI/11)),s*(-.72+i*.13)]),.035+w.severity*.018,'dry_line');}
  if(w.kind==='scratch'){if(w.severity>=2)disk(w,0,0,.75*s,.44*s,'abrasion');for(let i=0;i<2+w.severity;i++)line(w,[[-.65*s+i*.24*s,-.42*s],[-.45*s+i*.24*s,-.03*s],[-.40*s+i*.24*s,.45*s]],.035+w.severity*.015,'wound_edge');}
  if(w.kind==='scorch')for(let i=0;i<3+w.severity*2;i++){const a=i*2.3999,r=.20*Math.sqrt(i)*s;disk(w,Math.cos(a)*r,Math.sin(a)*r,.36*s,.26*s,'burn_edge',.78);disk(w,Math.cos(a)*r,Math.sin(a)*r,.29*s,.20*s,'charcoal');}
 },{woundId:w.id,kind:w.kind,side:w.side});
  const emitter=posed(w.anchor,false,mul(w.anchor.n,.07)),outward=norm(sub(posed(w.anchor,false,mul(w.anchor.n,1)),posed(w.anchor))),units=w.kind==='tear'?clamp(w.quantity||0,0,12):0;
  anchors.push({id:w.id,side:w.side,part:w.anchor.part,rest:w.anchor.p,position:emitter,normal:outward,weights:pre.influence(w.anchor.p)});
  for(let i=0;i<units;i++)part('filling_'+w.id+'_'+(i+1),()=>{const a=i*2.3999,spread=(i+1)*.03*cfg.scale,mode=w.mode??1;let p=emitter.slice(),radius=[.15,.18,.15].map(r=>r*cfg.scale);
   if(mode===0)p=add(p,mul(outward,.08*cfg.scale+spread));
   if(mode===1){p=add(p,mul(outward,.16*cfg.scale));p[1]-=(.12+i*.075)*cfg.scale;p[0]+=.09*Math.sin(a)*cfg.scale;radius=[.10,.20,.10].map(r=>r*cfg.scale);p[1]=Math.max(radius[1],p[1]);}
   if(mode===2){const speed=(2.0+(i%4)*.36)*cfg.scale,velocity=add(mul(outward,speed),[Math.sin(a)*cfg.scale,2.4*cfg.scale,Math.cos(a)*.4*cfg.scale]),g=10*cfg.scale,ground=.08*cfg.scale,duration=(velocity[1]+Math.sqrt(velocity[1]**2+2*g*Math.max(0,emitter[1]-ground)))/g,t=duration*cfg.flight;p=add(emitter,mul(velocity,t));p[1]-=g*t*t/2;radius=[.23,.25,.21].map(r=>r*cfg.scale);if(cfg.flight>=.98){p[1]=ground;radius=[.32,.07,.29].map(r=>r*cfg.scale);}}
   if(mode===2&&p[1]<radius[1]){p[1]=Math.max(.08*cfg.scale,p[1]);const ratio=Math.max(.28,(p[1]-.001*cfg.scale)/radius[1]);radius[1]=Math.min(radius[1],p[1]-.001*cfg.scale);radius[0]*=1+(1-ratio)*.35;radius[2]*=1+(1-ratio)*.35;}
   if(mode===3){p=add(emitter,[Math.cos(a)*.45*Math.sqrt(i+1)*cfg.scale,0,Math.sin(a)*.35*Math.sqrt(i+1)*cfg.scale]);p[1]=.09*cfg.scale;radius=[.58,.09,.46].map(r=>r*cfg.scale);}
   ellipsoid(p,radius,'deep_filling');
  },{woundId:w.id,unit:i+1,mode:w.mode});
 }
 const lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];for(let i=0;i<out.length;i+=8)for(let k=0;k<3;k++){lo[k]=Math.min(lo[k],out[i+k]);hi[k]=Math.max(hi[k],out[i+k]);}
 return {id:'body_wounds',vertices:out,parts,texture_size:512,dims:hi.map((x,i)=>x-lo[i]),anchors,recipe:cfg,picking,surfaceFrame:{center:pre.center,half:pre.half}};
}
function pick(scene,origin,direction){let best=null;for(const tri of scene.picking){const [a,b,c]=tri.points,e1=sub(b,a),e2=sub(c,a),h=cross(direction,e2),det=dot(e1,h);if(Math.abs(det)<1e-8)continue;const s=sub(origin,a),u=dot(s,h)/det,q=cross(s,e1),v=dot(direction,q)/det,t=dot(e2,q)/det;if(u<0||v<0||u+v>1||t<0||(best&&t>=best.t))continue;
 const rest=add(add(mul(tri.rest[0],1-u-v),mul(tri.rest[1],u)),mul(tri.rest[2],v)),side=Object.keys(sides).reduce((a,b)=>dot(sides[a].n,tri.n)>dot(sides[b].n,tri.n)?a:b),basis=sides[side],ranges=[basis.u,basis.v].map(v=>dot(v.map(Math.abs),scene.surfaceFrame.half)),d=sub(rest,scene.surfaceFrame.center);best={t,side,u:clamp(dot(d,basis.u)/ranges[0],-1,1),v:clamp(dot(d,basis.v)/ranges[1],-1,1)};
 }return best;}
function bbmodel(scene,texture){const elements=scene.parts.filter(p=>p.count).map((part,i)=>{const vertices={},faces={};for(let j=0;j<part.count;j++){const v=scene.vertices.slice((part.start+j)*8,(part.start+j)*8+8);vertices[j]=v.slice(0,3);if(j%3===2){const ids=[j-2,j-1,j].map(String);faces[(j-2)/3]={vertices:ids,uv:Object.fromEntries(ids.map(k=>[k,scene.vertices.slice((part.start+Number(k))*8+3,(part.start+Number(k))*8+5).map(n=>n*512)])),texture:0};}}
 return {name:part.name,type:'mesh',uuid:'dbfacede-2026-4000-8000-'+String(i).padStart(12,'0'),origin:[0,0,0],rotation:[0,0,0],vertices,faces};});
 return {meta:{format_version:'4.10',model_format:'free',box_uv:false,wound_recipe:scene.recipe},name:'body_wounds',resolution:{width:512,height:512},elements,outliner:[{name:'root',uuid:'dbfacede-2026-4000-9000-000000000000',origin:[0,0,0],children:elements.map(e=>e.uuid)}],textures:[{name:'texture.png',id:'0',uuid:'dbfacede-2026-4000-a000-000000000000',source:texture,mode:'bitmap',width:512,height:512,uv_width:512,uv_height:512}],animations:[]};
}
const api={sides,kinds,prepare,ray,locate,defaults,scene,pick,bbmodel};if(typeof module!=='undefined')module.exports=api;root.WoundSurface=api;
})(typeof globalThis!=='undefined'?globalThis:this);
