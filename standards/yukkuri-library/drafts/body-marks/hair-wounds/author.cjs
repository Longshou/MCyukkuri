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
  const kind=spec.kind||'tear',severity=spec.severity??1;
  const grade=recipe.grades[kind]?.[severity-1],core=!!spec.core;
  if(!grade||severity<1||severity>3)throw Error('Unknown injury type/grade');
  if(core&&(severity!==3||!recipe.core_openings[kind]))throw Error('Core needs a real grade-three deep injury');
  const open=grade.open,radii=spec.radius||(core?recipe.core_openings[kind]:grade.radius),[rx,ry]=radii;
  // A crushed body cracks within the indentation; it does not lose the whole dent.
  const openingScale=kind==='dent'?(core?.76:.55):kind==='scorch'?.82:1;
  const cutRx=rx*openingScale,cutRy=ry*openingScale;
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
  function rough(a){
    if(kind==='dent')return .82+.20*Math.cos(5*a+.3)+.06*Math.sin(11*a);
    if(kind==='scorch')return 1+.11*Math.sin(7*a+.4)+.055*Math.sin(13*a+1.1);
    return 1+.07*Math.sin(5*a+.4)+.045*Math.sin(13*a+1.1);
  }
  // Hair breaks in uneven strand ends INSIDE the tissue opening. The original
  // hair outside this boundary remains, including overhanging edges above the cavity.
  function hairBoundary(a){
    const vertical=Math.abs(Math.sin(a));
    if(kind==='tear')return rough(a)*(.96-.23*vertical+.075*Math.sin(19*Math.cos(a)+.4)*vertical);
    if(kind==='dent')return rough(a)*(.78+.09*Math.sin(11*a+.5));
    return rough(a)*(.87+.055*Math.sin(19*a));
  }
  const outline=Array.from({length:recipe.segments},(_,j)=>{const a=j*2*Math.PI/recipe.segments,k=rough(a);return [Math.cos(a)*k,Math.sin(a)*k];});
  const hairOutline=Array.from({length:recipe.segments},(_,j)=>{const a=j*2*Math.PI/recipe.segments,k=hairBoundary(a);return [Math.cos(a)*k,Math.sin(a)*k];});
  const colors=[],materials=[],out=[],surfaceEdits=[];
  if(kind==='dent'){
    // Displace the real skin and covering strands by the SAME field, never hide them.
    for(const part of [skin,...hair,...parts.filter(p=>p.name.startsWith('face_'))]){
      for(let index=part.start;index<part.start+part.count;index++){
        const p=Array.from(vertices.slice(index*8,index*8+3)),q=local(p),x=q[0]-cx,y=q[1]-cy;
        const r2=(x/rx)**2+(y/ry)**2;if(q[2]<.15||r2>=1)continue;
        const depth=grade.depth,fall=(1-r2)**3;
        const original=Array.from(vertices.slice(index*8+5,index*8+8)),nz=dot(original,n);
        const dx=-6*x/rx**2*(1-r2)**2,dy=-6*y/ry**2*(1-r2)**2;
        const normal=original.map((value,i)=>value+depth*nz*(dx*u[i]+dy*v[i])),len=Math.hypot(...normal);
        surfaceEdits.push([index,...p.map((value,i)=>value-n[i]*depth*fall),...normal.map(value=>value/len)]);
      }
    }
  }
  const depression=(x,y)=>kind==='dent'?grade.depth*Math.max(0,1-((x-cx)/rx)**2-((y-cy)/ry)**2)**3:0;
  if(kind==='scorch'&&severity>=2){
    for(const part of hair)for(let index=part.start;index<part.start+part.count;index++){
      const p=Array.from(vertices.slice(index*8,index*8+3)),q=local(p),x=(q[0]-cx)/rx,y=(q[1]-cy)/ry,r2=x*x+y*y;
      if(q[2]<.15||r2>=1)continue;
      const curl=.085*(severity-1)*(1-r2)**2*Math.sin(y*10+x*3);
      surfaceEdits.push([index,...p.map((value,i)=>value+n[i]*curl+v[i]*Math.abs(curl)*.45),...vertices.slice(index*8+5,index*8+8)]);
    }
  }
  function triangle(a,b,c,color,material){
    const ab=b.map((q,i)=>q-a[i]),ac=c.map((q,i)=>q-a[i]),normal=cross(ab,ac),length=Math.hypot(...normal);
    if(length<1e-9)return;
    const id=colors.length;colors.push(color.map(q=>Math.round(q)));materials.push(material);
    for(const p of [a,b,c])out.push(...p,-id-1,0,...normal.map(q=>q/length));
  }
  const skins=[],hairs=[];
  if(open){
    for(const [x,y] of outline){
      const px=cx+x*cutRx,py=cy+y*cutRy;
      const z=sample(skinTris,px,py);if(!Number.isFinite(z)||z<.2)throw Error('Wound outside same-side skin');skins.push(z-depression(px,py));
    }
    const N=outline.length;
    for(const [x,y] of hairOutline){
      const px=cx+x*cutRx,py=cy+y*cutRy,hz=sample(hairTris,px,py),sz=sample(skinTris,px,py);
      hairs.push(Number.isFinite(hz)&&hz>=sz-.15&&hz-sz<2.5?hz-depression(px,py):null);
    }
    // Small thickness at actual broken strand edges, never a shaved annulus.
    for(let j=0;j<N;j++){
      const k=(j+1)%N;if(hairs[j]===null||hairs[k]===null)continue;
      const p=(index,scale,depth)=>world(cx+hairOutline[index][0]*cutRx*scale,cy+hairOutline[index][1]*cutRy*scale,depth);
      const a=p(j,1.015,hairs[j]+.012),b=p(k,1.015,hairs[k]+.012),c=p(k,1,hairs[k]-.075),d=p(j,1,hairs[j]-.075);
      const col=kind==='scorch'?[47,37,29]:mix(model.hair_edge,model.hair_highlight,.18+.08*Math.sin(j*1.7));
      triangle(a,c,b,col,'hair_break');triangle(a,d,c,col,'hair_break');
      // Attached remnants hang over the cavity. They originate at surviving hair,
      // have uneven blunt/frayed ends, and are not particles or detached fragments.
      if(j%(kind==='dent'?6:4)===0&&(kind!=='tear'||Math.abs(Math.sin(j*2*Math.PI/N))>.45)){
        const angle=j*2*Math.PI/N,down=Math.sin(angle)>.1;
        const length=kind==='scorch'?.11:kind==='dent'?.24:.18+(down?.18:0);
        const tip=p(j,1-length,hairs[j]-(kind==='dent'?.32:.08));
        const base=p(j,1.055,hairs[j]+.02),side=p(k,1.055,hairs[k]+.02);
        const end=tip.map((q,i)=>q+u[i]*(kind==='scorch'?.035:.06));
        const material=kind==='scorch'?'singed_stub':kind==='dent'?'folded_lock':'torn_lock';
        triangle(base,tip,side,col,material);triangle(side,tip,end,col,material);
      }
    }
    const controls=core?
      [[1.04,-.01,0,[220,193,152]],[1.005,.025,0,[244,220,181]],[.97,.20,0,[212,177,138]],
       [.87,.78,.12,[116,78,61]],[.79,1.75,.34,[96,62,49]],[.72,3,.65,[77,46,40]],[.62,4.5,1,[58,34,32]],[0,5.7,1,[50,31,30]]]:
      [[1.04,-.01,0,[225,197,155]],[1.005,.02,0,[244,219,177]],[.94,.15,0,[206,168,129]],
       [.72,grade.depth*.5,0,[111,73,56]],[.40,grade.depth*.85,0,[85,51,43]],[0,grade.depth,0,[68,40,35]]];
    if(kind==='scorch'){
      controls[0][3]=[57,43,30];controls[1][3]=[83,57,34];controls[2][3]=[118,79,48];
    }
    const levels=[];
    for(let r=0;r<controls.length-1;r++){
      const a=controls[r],b=controls[r+1],steps=r<2?1:6;
      for(let j=0;j<steps;j++){const t=j/steps;levels.push([a[0]*(1-t)+b[0]*t,a[1]*(1-t)+b[1]*t,a[2]*(1-t)+b[2]*t,mix(a[3],b[3],t)]);}
    }
    levels.push(controls.at(-1));
    const rings=levels.map(([scale,depth,t])=>outline.map(([x,y],j)=>world(cx*(1-t)+x*cutRx*scale,cy*(1-t)+y*cutRy*scale,
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
    if(!open||!cuttable.has(partName))return false;
    const q=local([x,y,z]);if(q[2]<.15)return false;
    const a=(q[0]-cx)/cutRx,b=(q[1]-cy)/cutRy,angle=Math.atan2(b,a);
    return Math.hypot(a,b)<(model.hair_parts.includes(partName)?hairBoundary(angle):rough(angle));
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
    profile:{origin,u,v,n,center:[cx,cy],radius:radii,cutRadius:[cutRx,cutRy],outline,hairOutline,kind,severity,open,core,coreCenter:recipe.core_center,
      hairResponse:recipe.hair_response[kind],preserveHairOutsideBreak:true},
    hairSamples:hairs.filter(q=>q!==null).length};
}
module.exports={createHairWound};
