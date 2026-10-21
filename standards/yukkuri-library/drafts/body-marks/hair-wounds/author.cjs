/* Source geometry for layered hair wounds. Model-space art, no game state. */
'use strict';
const fs=require('fs'),path=require('path');
const recipe=JSON.parse(fs.readFileSync(path.join(__dirname,'recipe.json'),'utf8'));
const {createCavity}=require('../cavity.cjs');
const baseRecipe=JSON.parse(fs.readFileSync(path.join(__dirname,'../recipe.json'),'utf8'));
const dot=(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0);
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const mix=(a,b,t)=>a.map((v,i)=>v*(1-t)+b[i]*t);

function createHairWound(vertices,parts,spec){
  const model=recipe.models[spec.model];if(!model)throw Error('Missing hair registration: '+spec.model);
  const skin=parts.find(p=>p.name==='rounded_body_surface');
  const hair=parts.filter(p=>model.hair_parts.includes(p.name));
  for(const name of model.hair_parts)if(!parts.some(p=>p.name===name))throw Error('Missing registered part '+name);
  const az=spec.az??Math.PI,u=[Math.cos(az),0,Math.sin(az)],v=[0,1,0],n=[Math.sin(az),0,-Math.cos(az)];
  const origin=recipe.core_center,cx=spec.x??0,cy=spec.y??0.1;
  const kind=spec.kind||'tear',shape=recipe.wounds[kind];
  const radii=spec.radius||(kind==='dent'?recipe.dent.radius:shape?.radius??[1.9,1.1]),[rx,ry]=radii;
  const local=p=>{const d=p.map((x,i)=>x-origin[i]);return [dot(d,u),dot(d,v),dot(d,n)];};
  const world=(x,y,z)=>origin.map((q,i)=>q+x*u[i]+y*v[i]+z*n[i]);
  const triangles=list=>list.flatMap(part=>{
    const out=[];for(let i=part.start*8;i<(part.start+part.count)*8;i+=24)
      out.push([0,8,16].map(j=>local(Array.from(vertices.slice(i+j,i+j+3)))));return out;
  });
  const skinTris=triangles([skin]),hairTris=triangles(hair);
  function sample(tris,x,y){
    let z=-Infinity;
    for(const [a,b,c] of tris){
      const d=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]);if(Math.abs(d)<1e-9)continue;
      const wa=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/d;
      const wb=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/d,wc=1-wa-wb;
      if(Math.min(wa,wb,wc)>=-1e-6)z=Math.max(z,wa*a[2]+wb*b[2]+wc*c[2]);
    }return z;
  }
  const rough=a=>1+.062*Math.sin(a*3+.4)+.034*Math.sin(a*7+1.1)+.016*Math.sin(a*13+.8);
  const outline=Array.from({length:recipe.segments},(_,j)=>{const a=j*2*Math.PI/recipe.segments,k=rough(a);return [Math.cos(a)*k,Math.sin(a)*k];});
  const colors=[],materials=[],out=[],surfaceEdits=[];
  if(kind==='dent'){
    // Displace the real skin and covering strands by the SAME field, never hide them.
    for(const part of [skin,...hair,...parts.filter(p=>p.name.startsWith('face_'))]){
      for(let index=part.start;index<part.start+part.count;index++){
        const p=Array.from(vertices.slice(index*8,index*8+3)),q=local(p),x=q[0]-cx,y=q[1]-cy;
        const r2=(x/rx)**2+(y/ry)**2;if(q[2]<.15||r2>=1)continue;
        const depth=recipe.dent.depth,fall=(1-r2)**3;
        const original=Array.from(vertices.slice(index*8+5,index*8+8)),nz=dot(original,n);
        const dx=-6*x/rx**2*(1-r2)**2,dy=-6*y/ry**2*(1-r2)**2;
        const normal=original.map((value,i)=>value+depth*nz*(dx*u[i]+dy*v[i])),len=Math.hypot(...normal);
        surfaceEdits.push([index,...p.map((value,i)=>value-n[i]*depth*fall),...normal.map(value=>value/len)]);
      }
    }
  }
  function triangle(a,b,c,color,material){
    const ab=b.map((q,i)=>q-a[i]),ac=c.map((q,i)=>q-a[i]),normal=cross(ab,ac),length=Math.hypot(...normal);
    if(length<1e-9)return;
    const id=colors.length;colors.push(color.map(q=>Math.round(q)));materials.push(material);
    for(const p of [a,b,c])out.push(...p,-id-1,0,...normal.map(q=>q/length));
  }
  const skins=[],hairs=[];
  if(shape){
    for(const [x,y] of outline){
      const z=sample(skinTris,cx+x*rx,cy+y*ry);if(!Number.isFinite(z)||z<.2)throw Error('Wound outside same-side skin');skins.push(z);
      const hz=sample(hairTris,cx+x*rx*recipe.hair_opening_scale,cy+y*ry*recipe.hair_opening_scale);
      hairs.push(Number.isFinite(hz)&&hz>=z-.15&&hz-z<2.5?hz:null);
    }
    const N=outline.length;
    // Hair cut edge joins the real skin; it is never a pale lip floating above hair.
    for(let j=0;j<N;j++){
      const k=(j+1)%N;if(hairs[j]===null||hairs[k]===null)continue;
      const p=(index,scale,depth)=>world(cx+outline[index][0]*rx*scale,cy+outline[index][1]*ry*scale,depth);
      const a=p(j,recipe.hair_opening_scale,hairs[j]+.012),b=p(k,recipe.hair_opening_scale,hairs[k]+.012),c=p(k,1.04,skins[k]+.018),d=p(j,1.04,skins[j]+.018);
      const col=mix(model.hair_edge,model.hair_highlight,.15+.12*Math.sin(j*1.7));
      triangle(a,c,b,col,'hair_cut');triangle(a,d,c,col,'hair_cut');
      // Short tapered strands, following the rim rather than radial white spikes.
      if(j%3===0){
        const tip=p(j,1.035,hairs[j]-.07),base=p(j,recipe.hair_opening_scale+.02,hairs[j]+.018);
        const side=base.map((q,i)=>q+u[i]*.025);
        triangle(base,side,tip,model.hair_highlight,'hair_tip');
      }
    }
    const core=shape.core;
    const controls=core?
      [[1.04,-.01,0,[220,193,152]],[1.005,.025,0,[244,220,181]],[.97,.20,0,[212,177,138]],
       [.87,.78,.12,[116,78,61]],[.79,1.75,.34,[96,62,49]],[.72,3,.65,[77,46,40]],[.62,4.5,1,[58,34,32]],[0,5.7,1,[50,31,30]]]:
      [[1.04,-.01,0,[225,197,155]],[1.005,.02,0,[244,219,177]],[.94,.15,0,[206,168,129]],
       [.72,.36,0,[111,73,56]],[.40,.55,0,[85,51,43]],[0,shape.depth,0,[68,40,35]]];
    const levels=[];
    for(let r=0;r<controls.length-1;r++){
      const a=controls[r],b=controls[r+1],steps=r<2?1:6;
      for(let j=0;j<steps;j++){const t=j/steps;levels.push([a[0]*(1-t)+b[0]*t,a[1]*(1-t)+b[1]*t,a[2]*(1-t)+b[2]*t,mix(a[3],b[3],t)]);}
    }
    levels.push(controls.at(-1));
    const rings=levels.map(([scale,depth,t])=>outline.map(([x,y],j)=>world(cx*(1-t)+x*rx*scale,cy*(1-t)+y*ry*scale,
      core&&depth>0?skins[j]*(1-depth/5.7)-1.5*depth/5.7:skins[j]-depth)));
    for(let r=0;r<rings.length-1;r++)for(let j=0;j<N;j++){
      const k=(j+1)%N,a=rings[r][j],b=rings[r][k],c=rings[r+1][k],d=rings[r+1][j];
      const col=levels[r][3].map(q=>q*(.98+.035*Math.sin(j*2.13+r*4.1))),mat=r<2?'dough':'filling';
      triangle(a,c,b,col,mat);triangle(a,d,c,col,mat);
    }
    // Reuse the selected core geometry and its broken material; no new organ design.
    if(core&&spec.state!=='empty'){
      const existing=createCavity(vertices,skin,baseRecipe.core_cavity,spec.state==='broken');
      for(let i=0;i<existing.vertices.length;i+=24){const id=-1-existing.vertices[i+3];if(existing.materials[id]!=='core')continue;
        const points=[0,8,16].map(j=>existing.vertices.slice(i+j,i+j+3));
        const q=local(points[0].map((_,k)=>(points[0][k]+points[1][k]+points[2][k])/3));
        const light=.62+.36*Math.min(1,Math.max(0,q[2])/1.12)+.14*Math.max(0,q[1]);
        const gleam=Math.exp(-((q[0]+.30)**2/.19+(q[1]-.33)**2/.11))*Math.max(0,q[2]);
        let color=[91,52,56].map((c,k)=>c*light+[15,12,11][k]*gleam);
        const cut=Math.abs(q[1]-.18*Math.sin(q[0]*7)-.12*q[0])<.075;
        const branch=q[0]>.05&&Math.abs(q[1]+.55*q[0]+.12)<.052;
        if(spec.state==='broken'&&q[2]>0&&(cut||branch))color=[28,18,21];
        triangle(...points,color,'core');
      }
    }
  }
  const cuttable=new Set([skin.name,...hair.map(p=>p.name),...parts.filter(p=>p.name.startsWith('face_')).map(p=>p.name)]);
  function inside(x,y,z,partName){
    if(!shape||!cuttable.has(partName))return false;
    const q=local([x,y,z]);if(q[2]<.15)return false;
    const scale=model.hair_parts.includes(partName)?recipe.hair_opening_scale:1;
    const a=(q[0]-cx)/(rx*scale),b=(q[1]-cy)/(ry*scale);
    return Math.hypot(a,b)<rough(Math.atan2(b,a));
  }
  // Smooth the filling and core only; preserve cut hair and dough thickness edges.
  const normals=new Map();
  for(let i=0;i<out.length;i+=8){const mat=materials[-1-out[i+3]];if(!['filling','core'].includes(mat))continue;
    const key=mat+out.slice(i,i+3).map(q=>Math.round(q*1e5)).join(',');
    const normal=normals.get(key)||[0,0,0];for(let k=0;k<3;k++)normal[k]+=out[i+5+k];normals.set(key,normal);
  }
  for(let i=0;i<out.length;i+=8){const mat=materials[-1-out[i+3]],key=mat+out.slice(i,i+3).map(q=>Math.round(q*1e5)).join(',');
    const normal=normals.get(key);if(normal){const length=Math.hypot(...normal);for(let k=0;k<3;k++)out[i+5+k]=normal[k]/length;}
  }
  return {vertices:out,colors,materials,surfaceEdits,inside,cuttable,paintable:new Set([skin.name,...hair.map(p=>p.name)]),
    project:p=>{const q=local(p);return [(q[0]-cx)/rx,(q[1]-cy)/ry,q[2]];},
    profile:{origin,u,v,n,center:[cx,cy],radius:radii,outline,hairScale:recipe.hair_opening_scale,coreCenter:recipe.core_center},
    hairSamples:hairs.filter(q=>q!==null).length};
}
module.exports={createHairWound};
