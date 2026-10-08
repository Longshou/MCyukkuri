/* Placement fit author: seat headwear and let hair follow the rounded head.

   Pure geometry; reads one base runtime model and its recipe entry, returns the
   runtime fit entry. Parts are changed only in rest space: refined (crack-free
   edge splits), deformed by the recipe steps, slice weights rebound by their new
   height, and bone pivots carried by the same transform. Unrefined parts keep
   their triangle order, so badge mounts and puff offsets keep their indices.
   Used by scripts/export_placement_fit.cjs; never writes files. */
'use strict';
const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
const sub=(a,b)=>[a[0]-b[0],a[1]-b[1],a[2]-b[2]],cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];

/* ---- deformations (rest space, one point at a time) ---- */
function matrixOf(step){
 // rotate about pivot (degrees, X then Z), scale about pivot, then translate.
 const [px,py,pz]=step.pivot||[0,0,0],[sx,sy,sz]=step.scale||[1,1,1],t=step.translate||[0,0,0];
 const ax=(step.rotate_x||0)*Math.PI/180,az=(step.rotate_z||0)*Math.PI/180;
 const cx=Math.cos(ax),snx=Math.sin(ax),cz=Math.cos(az),snz=Math.sin(az);
 const R=[[cz,-snz*cx,snz*snx],[snz,cz*cx,-cz*snx],[0,snx,cx]];
 const m=[];for(let r=0;r<3;r++){m.push(R[r][0]*sx,R[r][1]*sy,R[r][2]*sz);m.push(0);}
 // m*(p-pivot)+pivot+t
 for(let r=0;r<3;r++)m[r*4+3]=[px,py,pz][r]+t[r]-(m[r*4]*px+m[r*4+1]*py+m[r*4+2]*pz);
 return m;
}
const affine=(m,p)=>[m[0]*p[0]+m[1]*p[1]+m[2]*p[2]+m[3],m[4]*p[0]+m[5]*p[1]+m[6]*p[2]+m[7],m[8]*p[0]+m[9]*p[1]+m[10]*p[2]+m[11]];
/* Top-edge rounding of a hair box: points above top-radii.y and outside the inner box map square->disc
   in the normalised (plan distance, height) plane. Points on the flat top or below the band stay. */
function dome(step,p){
 const [x0,x1,z0,z1,top]=step.box,r=step.radii,base=top-r.y,dy=(p[1]-base)/r.y;if(dy<=0)return p;
 const cx=clamp(p[0],x0+r.x,x1-r.x),dx=Math.abs(p[0]-cx)/r.x;
 let cz=p[2],dz=0;if(p[2]<z0+r.front){cz=z0+r.front;dz=(cz-p[2])/r.front;}else if(p[2]>z1-r.back){cz=z1-r.back;dz=(p[2]-cz)/r.back;}
 const h=Math.max(dx,dz);if(h<=0)return p;
 const f=Math.max(h,dy)/Math.hypot(h,dy);
 return [cx+(p[0]-cx)*f,base+(p[1]-base)*f,cz+(p[2]-cz)*f];
}
/* Height profile about a vertical axis: knots [y, dy, sx, sz], linear between, held beyond the ends. */
function taper(step,p){
 const k=step.knots;let i=0;while(i<k.length-2&&p[1]>k[i+1][0])i++;
 const a=k[i],b=k[i+1],q=clamp((p[1]-a[0])/(b[0]-a[0]),0,1),lerp=j=>a[j]+(b[j]-a[j])*q;
 const [cx,cz]=step.center||[0,0];return [cx+(p[0]-cx)*lerp(2),p[1]+lerp(1),cz+(p[2]-cz)*lerp(3)];
}
/* Vertical drape: lower by D(|x-cx|) (piecewise linear knots [distance, drop]) times a height ramp
   from y0 (no drop) to y1 (full drop). Points never move sideways, so a lock keeps its hinge spacing. */
function drape(step,p){
 const k=step.knots,d=Math.abs(p[0]-(step.center_x||0));let i=0;while(i<k.length-2&&d>k[i+1][0])i++;
 const a=k[i],b=k[i+1],q=clamp((d-a[0])/(b[0]-a[0]),0,1),drop=a[1]+(b[1]-a[1])*q,w=clamp((p[1]-step.y0)/(step.y1-step.y0),0,1);
 return [p[0],p[1]-drop*w,p[2]];
}
function mapper(step){
 if(step.op==='drape')return p=>drape(step,p);
 if(step.op==='follow'){const a=step.anchor,m=dome(step,a),d=[m[0]-a[0],m[1]-a[1],m[2]-a[2]];return p=>[p[0]+d[0],p[1]+d[1],p[2]+d[2]];}
 if(step.op==='affine'||step.op==='seat')return p=>affine(step.matrix,p);
 if(step.op==='dome')return p=>dome(step,p);
 if(step.op==='taper')return p=>taper(step,p);
 if(step.op==='bind'||step.op==='lift'||step.op==='conform')return p=>p;
 throw Error('Unknown placement step '+step.op);
}

/* ---- working geometry ---- */
class Working{
 constructor(model){this.model=model;this.parts=new Map();this.pivots=model.bones.map(b=>b.pivot.slice());this.families=new Map();
  model.bones.forEach((b,i)=>{const m=/^(.*_slice)_\d+_bend$/.exec(b.name);if(m){if(!this.families.has(m[1]))this.families.set(m[1],[]);this.families.get(m[1]).push(i);}});
  for(const list of this.families.values())list.sort((a,b)=>model.bones[a].pivot[1]-model.bones[b].pivot[1]);
  this.familyOf=new Map();for(const list of this.families.values())for(const i of list)this.familyOf.set(i,list);}
 tris(index){
  if(this.parts.has(index))return this.parts.get(index).tris;
  const p=this.model.parts[index],V=this.model.vertices,out=[];
  for(let i=p.start;i<p.start+p.count;i+=3)out.push({v:[0,1,2].map(j=>V.slice((i+j)*8,(i+j)*8+5)),w:[0,1,2].map(j=>this.model.weights[i+j].slice())});
  return out;}
 edit(index){if(!this.parts.has(index))this.parts.set(index,{tris:this.tris(index),refined:false});return this.parts.get(index);}
 refine(index,fn,tol){const e=this.edit(index),before=e.tris.length;e.tris=refine(e.tris,fn,tol);if(e.tris.length!==before)e.refined=true;}
 rebind(w,y){
  // Slice bindings interpolate between the two family bones around the rest height.
  const list=this.familyOf.get(w[0]);if(!list||this.familyOf.get(w[1])!==list)return w;
  const bones=this.model.bones;let j=0;while(j<list.length-2&&y>bones[list[j+1]].pivot[1])j++;
  const a=list[j],b=list[j+1];return [a,b,clamp((y-bones[a].pivot[1])/(bones[b].pivot[1]-bones[a].pivot[1]),0,1)];
 }
 bind(index,family){
  // A rigid block (the scalp filler under the hair cap) takes the cap's height slices, so both squash together.
  const list=this.families.get(family);if(!list)throw Error('No slice family '+family);const e=this.edit(index);
  for(const t of e.tris)for(let j=0;j<3;j++)t.w[j]=this.rebind([list[0],list[1],0],t.v[j][1]);}
 apply(index,fn,rebind){const e=this.edit(index);
  for(const t of e.tris)for(let j=0;j<3;j++){const q=fn(t.v[j]);t.v[j]=[q[0],q[1],q[2],t.v[j][3],t.v[j][4]];if(rebind)t.w[j]=this.rebind(t.w[j],q[1]);}}
 points(indices){const out=[];for(const i of indices)for(const t of this.tris(i))for(const v of t.v)out.push(v);return out;}
 triangles(indices){const out=[];for(const i of indices)for(const t of this.tris(i))out.push(t.v.map(v=>[v[0],v[1],v[2]]));return out;}
}
/* Crack-free adaptive refinement: an edge splits iff the deformation bends it by more than tol
   (mapped midpoint away from the mapped chord). The test depends on the edge alone, so both
   neighbours agree. Straight (affine) regions keep their original triangles. */
function refine(tris,fn,tol){
 const mid=(a,b)=>a.map((x,k)=>(x+b[k])/2),len=(a,b)=>Math.hypot(a[0]-b[0],a[1]-b[1],a[2]-b[2]);
 const bent=(a,b)=>{if(len(a,b)<0.05)return false;const m=fn(mid(a,b)),fa=fn(a),fb=fn(b);return len(m,mid(fa,fb))>tol;};
 for(let pass=0;pass<10;pass++){let changed=false;const out=[];
  for(const t of tris){
   const long=[0,1,2].map(e=>bent(t.v[e],t.v[(e+1)%3])),n=long.filter(Boolean).length;if(!n){out.push(t);continue;}changed=true;
   const m=[0,1,2].map(e=>long[e]?{v:mid(t.v[e],t.v[(e+1)%3]),w:t.w[e].slice()}:null),c=[0,1,2].map(j=>({v:t.v[j],w:t.w[j]}));
   const tri=(...k)=>({v:k.map(x=>x.v),w:k.map(x=>x.w)});
   if(n===3)out.push(tri(c[0],m[0],m[2]),tri(m[0],c[1],m[1]),tri(m[2],m[1],c[2]),tri(m[0],m[1],m[2]));
   else if(n===1){const e=long.indexOf(true);out.push(tri(c[e],m[e],c[(e+2)%3]),tri(m[e],c[(e+1)%3],c[(e+2)%3]));}
   else{const e=long.indexOf(false),P=c[e],Q=c[(e+1)%3],S=c[(e+2)%3],mq=m[(e+1)%3],ms=m[(e+2)%3];
    out.push(tri(P,Q,mq),tri(P,mq,ms),tri(mq,S,ms));}
  }
  tris=out;if(!changed)break;}
 return tris;
}

/* ---- geometric queries for solvers ---- */
function rayHit(p,d,T){const e1=sub(T[1],T[0]),e2=sub(T[2],T[0]),h=cross(d,e2),det=dot(e1,h);if(Math.abs(det)<1e-12)return -1;const f=1/det,s=sub(p,T[0]),u=f*dot(s,h);if(u<0||u>1)return -1;const q=cross(s,e1),v=f*dot(d,q);if(v<0||u+v>1)return -1;const t=f*dot(e2,q);return t>1e-9?t:-1;}
/* Distance a group can travel along dir before its points meet the target surface (minus the allowed overlap). */
function travel(points,targetTris,dir){
 // A point whose nearest hit leaves the target is already buried and does not limit the travel.
 let best=Infinity;for(const p of points){let near=Infinity,entering=false;
  for(const T of targetTris){const t=rayHit(p,dir,T);if(t>0&&t<near){near=t;entering=dot(cross(sub(T[1],T[0]),sub(T[2],T[0])),dir)<0;}}
  if(entering&&near<best)best=near;}
 return best;
}

function samplesOn(T,spacing){const n=Math.max(1,Math.ceil(Math.max(...[0,1,2].map(e=>Math.hypot(...sub(T[e],T[(e+1)%3]))))/spacing)),out=[];
 for(let i=0;i<=n;i++)for(let j=0;j<=n-i;j++){const a=i/n,b=j/n,c=1-a-b;out.push([0,1,2].map(k=>T[0][k]*a+T[1][k]*b+T[2][k]*c));}return out;}

/* ---- recipe resolution ---- */
function partsMatching(model,pattern,exclude){
 const re=new RegExp('^(?:'+pattern+')$'),ex=exclude?new RegExp('^(?:'+exclude+')$'):null;
 const out=[];model.parts.forEach((p,i)=>{if(re.test(p.name)&&!(ex&&ex.test(p.name)))out.push(i);});
 if(!out.length)throw Error(model.id+': no part matches '+pattern);return out;
}
function bonesMatching(model,pattern){if(!pattern)return [];const re=new RegExp('^(?:'+pattern+')$');return model.bones.map((b,i)=>re.test(b.name)?i:-1).filter(i=>i>=0);}
function bounds(points){const lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];for(const p of points)for(let a=0;a<3;a++){lo[a]=Math.min(lo[a],p[a]);hi[a]=Math.max(hi[a],p[a]);}return {lo,hi};}

function author(model,entry){
 const work=new Working(model),log=[];
 for(const raw of entry.steps){
  const step={...raw},parts=partsMatching(model,step.parts,step.exclude),bones=bonesMatching(model,step.bones);
  if(step.op==='dome'||step.op==='follow'){
   const b=bounds(work.points(step.box_from?partsMatching(model,step.box_from):parts)),box=[b.lo[0],b.hi[0],b.lo[2],b.hi[2],b.hi[1]];
   step.box=(step.box||[null,null,null,null,null]).map((v,i)=>v??box[i]);
  }
  if(step.op==='affine')step.matrix=matrixOf(step);
  if(step.op==='seat'){
   // Optional rigid tilt about the pivot, then translation along dir until the contact points meet
   // the target surface, keeping the given overlap (positive sinks into the target).
   const tilt=matrixOf({pivot:step.pivot,rotate_x:step.rotate_x,rotate_z:step.rotate_z});
   const dir=step.direction,n=Math.hypot(...dir),d=dir.map(x=>x/n),contact=step.contact?partsMatching(model,step.contact):parts;
   const target=work.triangles(partsMatching(model,step.target,step.target_exclude));
   // Contact points sample the contact faces (every 0.25 units), optionally only each part's bottom face.
   let points=[];for(const i of contact){const tris=work.triangles([i]),low=Math.min(...tris.flat().map(p=>p[1]));
    const faces=tris.filter(T=>!(step.contact_bottom&&T.some(p=>p[1]>low+0.02)));
    if(step.contact_edge){
     // Only the outline of the selected faces: a worn cap meets the hair along its rim, the inside stays hidden.
     const key=p=>p.map(v=>Math.round(v*1e4)).join(','),count=new Map();
     for(const T of faces)for(let e=0;e<3;e++){const a=key(T[e]),b=key(T[(e+1)%3]),k=a<b?a+'|'+b:b+'|'+a;count.set(k,(count.get(k)||0)+1);}
     for(const T of faces)for(let e=0;e<3;e++){const a=T[e],b=T[(e+1)%3],ka=key(a),kb=key(b);if(count.get(ka<kb?ka+'|'+kb:kb+'|'+ka)!==1)continue;
      const n=Math.max(1,Math.ceil(Math.hypot(...sub(a,b))/0.25));for(let i=0;i<=n;i++)points.push(a.map((v,k)=>v+(b[k]-v)*i/n));}
    }else for(const T of faces)points.push(...samplesOn(T,0.25));}
   const free=travel(points.map(p=>affine(tilt,p)),target,d);
   if(!Number.isFinite(free))throw Error(model.id+': seat never reaches '+step.target);
   const distance=Math.max(0,free+(step.overlap||0)),m=tilt.slice();for(let r=0;r<3;r++)m[r*4+3]+=d[r]*distance;
   step.matrix=m;log.push({step:raw.note||raw.op,travel:+distance.toFixed(4)});
  }
  const fn=mapper(step);
  if(step.refine)for(const i of parts)work.refine(i,fn,step.refine);
  // Rigid riders (ears, small ornaments) keep their shape and move by the displacement at their root.
  for(const g of step.rigid||[]){const anchor=g.anchor,moved=fn(anchor),d=sub(moved,anchor).map((v,k)=>v*(g.axes?g.axes[k]:1)),m=matrixOf({translate:d}),gm=p=>affine(m,p);
   for(const i of partsMatching(model,g.parts))work.apply(i,gm,false);for(const b of bonesMatching(model,g.bones))work.pivots[b]=gm(work.pivots[b]);
   log.push({step:'rider '+g.parts,translate:d.map(x=>+x.toFixed(4))});}
  if(step.op==='bind'){for(const i of parts)work.bind(i,step.family);continue;}
  if(step.op==='conform'){
   // A thin ribbon lies on the target: every point moves along dir so the ribbon's inner face sits
   // gap outside the target's far surface at that spot. Same shift through the thickness, so it bends, not squashes.
   const dir=step.direction,n=Math.hypot(...dir),d=dir.map(x=>x/n),target=work.triangles(partsMatching(model,step.target,step.target_exclude));
   const far=p=>{let best=-Infinity;for(const T of target){const e1=sub(T[1],T[0]),e2=sub(T[2],T[0]),h=cross(d,e2),det=dot(e1,h);if(Math.abs(det)<1e-12)continue;
     const f=1/det,s0=sub(p,T[0]),u=f*dot(s0,h);if(u<0||u>1)continue;const q=cross(s0,e1),v=f*dot(d,q);if(v<0||u+v>1)continue;best=Math.max(best,f*dot(e2,q));}return best;};
   for(const i of parts){const inner=Math.min(...work.points([i]).map(p=>dot(p,d)));
    const fn=p=>{const s0=far(p);if(s0===-Infinity)return p;const shift=Math.max(0,dot(p,d)+s0+(step.gap||0)-inner);return [p[0]+d[0]*shift,p[1]+d[1]*shift,p[2]+d[2]*shift];};
    if(step.refine)work.refine(i,fn,step.refine);work.apply(i,fn,false);}
   log.push({step:raw.note||'conform'});continue;}
  if(step.op==='lift'){
   // Each piece moves rigidly along dir just far enough that none of its corners stays inside the target.
   const dir=step.direction,n=Math.hypot(...dir),d=dir.map(x=>x/n),target=work.triangles(partsMatching(model,step.target,step.target_exclude));
   for(const i of parts){let depth=0;for(const p of work.points([i])){let near=Infinity,leaving=false;
     for(const T of target){const t=rayHit(p,d,T);if(t>0&&t<near){near=t;leaving=dot(cross(sub(T[1],T[0]),sub(T[2],T[0])),d)>0;}}
     if(leaving)depth=Math.max(depth,near);}
    if(depth>0){const m=matrixOf({translate:d.map(x=>x*(depth+(step.gap||0)))});work.apply(i,p=>affine(m,p),false);log.push({step:'lift '+model.parts[i].name,distance:+(depth+(step.gap||0)).toFixed(4)});}}
   continue;}
  for(const i of parts)work.apply(i,fn,step.rebind!==false);
  for(const b of bones)work.pivots[b]=fn(work.pivots[b]);
  if(step.op==='dome')log.push({step:raw.note||'dome',box:step.box.map(v=>+v.toFixed(4))});
  if(step.op==='follow'){const q=fn(step.anchor);log.push({step:raw.note||'follow',translate:q.map((v,k)=>+(v-step.anchor[k]).toFixed(4))});}
 }
 return {work,log};
}

function unchanged(model,index,e){const p=model.parts[index];let k=p.start;
 for(const t of e.tris)for(let j=0;j<3;j++,k++){for(let a=0;a<5;a++)if(Math.abs(t.v[j][a]-model.vertices[k*8+a])>1e-7)return false;const w=model.weights[k];if(t.w[j][0]!==w[0]||t.w[j][1]!==w[1]||Math.abs(t.w[j][2]-w[2])>1e-7)return false;}
 return true;}
/* Indexed runtime entry; normals are recomputed flat by every consumer. */
function encode(model,work){
 const parts=[];
 for(const [index,e] of [...work.parts].sort((a,b)=>a[0]-b[0])){
  if(!e.refined&&unchanged(model,index,e))continue;
  const keys=new Map(),vertices=[],weights=[],indices=[];
  for(const t of e.tris){
   const area=Math.hypot(...cross(sub(t.v[1],t.v[0]),sub(t.v[2],t.v[0])));
   for(let j=0;j<3;j++){
    const v=t.v[j].map(x=>+x.toFixed(5)),w=[t.w[j][0],t.w[j][1],+t.w[j][2].toFixed(6)],key=v.join(',')+'|'+w.join(',');
    let k=keys.get(key);if(k===undefined){k=keys.size;keys.set(key,k);vertices.push(...v);weights.push(...w);}indices.push(k);
   }
   if(!(area>=0))throw Error('Invalid triangle');
  }
  const inplace=!e.refined;if(inplace&&indices.length!==model.parts[index].count)throw Error('In-place topology changed');
  parts.push({part_index:index,name:model.parts[index].name,inplace,vertices,weights,indices});
 }
 const pivots=[];work.pivots.forEach((p,i)=>{const o=model.bones[i].pivot;if(Math.hypot(p[0]-o[0],p[1]-o[1],p[2]-o[2])>1e-9)pivots.push({index:i,name:model.bones[i].name,pivot:p.map(x=>+x.toFixed(6))});});
 return {parts,bone_pivots:pivots};
}

module.exports={author,encode,refine,dome,taper,matrixOf,partsMatching,bounds};
