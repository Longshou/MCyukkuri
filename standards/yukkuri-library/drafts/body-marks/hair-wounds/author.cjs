/* Source geometry for layered hair wounds. Model-space art, no game state. */
'use strict';
const fs=require('fs'),path=require('path');
const recipe=JSON.parse(fs.readFileSync(path.join(__dirname,'recipe.json'),'utf8'));
const {createCavity,expandOutline,insidePolygon,wallTone}=require('../cavity.cjs');
const provenance=JSON.parse(fs.readFileSync(path.join(__dirname,'sources.json'),'utf8'));
const baseRecipe=JSON.parse(fs.readFileSync(path.join(__dirname,'../recipe.json'),'utf8'));
const {modelInfo,fitFor}=require('./species.cjs');
const dot=(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0);
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const mix=(a,b,t)=>a.map((v,i)=>v*(1-t)+b[i]*t);

function createHairWound(vertices,parts,spec){
  const fit=fitFor(spec.model),info=modelInfo(spec.model);
  if(info.house)throw Error('House variants only receive thermal art');
  if(fit.scale.every(v=>v===1)&&fit.offset.every(v=>v===0)){
    const result=authorCanonical(vertices,parts,spec);result.profile.canonicalToModel=info.fit;return result;
  }
  const canonical=Float64Array.from(vertices);
  for(let k=0;k<canonical.length;k+=8){canonical.set(fit.toCanonical(Array.from(vertices.slice(k,k+3))),k);canonical.set(fit.normalToCanonical(Array.from(vertices.slice(k+5,k+8))),k+5);}
  const result=authorCanonical(canonical,parts,spec),inside=result.inside,project=result.project;
  function mapVertices(values){const out=Array.from(values);for(let k=0;k<out.length;k+=8){out.splice(k,3,...fit.toModel(out.slice(k,k+3)));out.splice(k+5,3,...fit.normalToModel(out.slice(k+5,k+8)));}return out;}
  result.vertices=mapVertices(result.vertices);
  result.surfacePatches=result.surfacePatches.map(p=>({...p,vertices:mapVertices(p.vertices)}));
  result.surfaceEdits=result.surfaceEdits.map(row=>[row[0],...fit.toModel(row.slice(1,4)),...fit.normalToModel(row.slice(4,7))]);
  result.inside=(x,y,z,part)=>inside(...fit.toCanonical([x,y,z]),part);
  result.project=p=>project(fit.toCanonical(p));
  result.profile.coordinateSpace='canonical body; apply canonicalToModel to profile coordinates, exported vertices already in model units';
  result.profile.canonicalToModel=info.fit;
  return result;
}
function authorCanonical(vertices,parts,spec){
  const model=modelInfo(spec.model);
  const skin=parts.find(p=>p.name===model.body_part);
  const hair=parts.filter(p=>model.hair_parts.includes(p.name));
  for(const name of model.hair_parts)if(!parts.some(p=>p.name===name))throw Error('Missing registered part '+name);
  const az=spec.az??Math.PI,u=[Math.cos(az),0,Math.sin(az)],v=[0,1,0],n=[Math.sin(az),0,-Math.cos(az)];
  const origin=recipe.core_center,cx=spec.x??0,cy=spec.y??0.1;
  const kind=spec.kind||'tear',style=spec.style||kind,severity=spec.severity??1;
  const grade=recipe.grades[style]?.[severity-1],core=!!spec.core;
  if(!['normal','intact'].includes(spec.state))throw Error('Only ordinary injury or intact exposed central filling is authored');
  const damage=spec.damage;
  if(!damage||damage.acceptedLoss<=0||Math.min(3,Math.ceil(damage.acceptedLoss/recipe.grading.divisor))!==severity||Math.abs(damage.healthBefore-damage.acceptedLoss-damage.healthAfter)>1e-6)throw Error('Missing actual-loss grade example');
  if(core&&(!damage.fittedOpening||damage.healthAfter/damage.maxHealth>recipe.grading.core_health_ratio))throw Error('Core exposure needs real depth and health conditions');
  if(!grade||severity<1||severity>3)throw Error('Unknown injury type/grade');
  if(!provenance.causes[spec.cause]||provenance.causes[spec.cause].kind!==kind||provenance.causes[spec.cause].style!==style)throw Error('Missing or mismatched injury cause');
  if(core&&(severity!==3||!recipe.core_openings[style]))throw Error('Core needs a real grade-three deep injury');
  const open=grade.open,radii=spec.radius||(core?recipe.core_openings[style]:grade.radius),[rx,ry]=radii;
  // A crushed body cracks within the indentation; it does not lose the whole dent.
  const openingScale=kind==='dent'?(core ? .76 : .42):1;
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
  // Hand-authored, radial low-poly silhouettes. Extra collinear vertices keep
  // runtime clipping and rim binding stable without smoothing away the shape.
  function hairScale(x,y,a){
    const vertical=Math.abs(y);
    if(style==='bite')return .94-.025*vertical;
    if(style==='puncture'||style==='impale'||style==='blast')return .88+.04*Math.sin(13*a);
    if(kind==='tear')return .96-.20*vertical+.05*Math.sin(19*x+.4)*vertical;
    if(kind==='dent')return .88+.04*Math.sin(11*a+.5);
    return .92;
  }
  const sourceOutline=style==='tear'?baseRecipe.core_cavity.outline:(recipe.outlines[style+'_'+severity]||recipe.outlines[style]||recipe.outlines.closed);
  const outline=expandOutline(sourceOutline,recipe.segments);
  const hairOutline=outline.map(([x,y])=>{const a=Math.atan2(y,x),k=hairScale(x,y,a);return [x*k,y*k];});
  const colors=[],materials=[],out=[],surfaceEdits=[],surfacePatches=[];
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
  if(kind==='dent'){
    // Skin and face use very different tessellation. Moving only their original
    // vertices pushes coarse eyes behind the finer skin. Subdivide every affected
    // receiver triangle before applying the same field, preserving its own UVs.
    for(const part of [skin,...hair,...parts.filter(p=>p.name.startsWith('face_'))]){
      for(let index=part.start;index<part.start+part.count;index+=3){
        const tri=[0,1,2].map(j=>Array.from(vertices.slice((index+j)*8,(index+j+1)*8)));
        const q=tri.map(p=>local(p.slice(0,3)));
        if(Math.max(...q.map(p=>p[2]))<.15||Math.min(...q.map(p=>p[0]))>cx+rx||Math.max(...q.map(p=>p[0]))<cx-rx||Math.min(...q.map(p=>p[1]))>cy+ry||Math.max(...q.map(p=>p[1]))<cy-ry)continue;
        const steps=Math.max(1,Math.ceil(Math.max(...tri.map((p,j)=>Math.hypot(...p.slice(0,3).map((v,k)=>v-tri[(j+1)%3][k]))))/.16));
        const patch=[];
        function vertex(a,b){
          const wa=1-a/steps-b/steps,wb=a/steps,wc=b/steps;
          const p=tri[0].map((v,k)=>v*wa+tri[1][k]*wb+tri[2][k]*wc),z=local(p.slice(0,3));
          const depth=z[2]>.15?depression(z[0],z[1]):0;
          for(let k=0;k<3;k++)p[k]-=n[k]*depth;
          const r2=((z[0]-cx)/rx)**2+((z[1]-cy)/ry)**2;
          if(z[2]>.15&&r2<1){
            const nz=dot(p.slice(5,8),n),dx=-6*(z[0]-cx)/rx**2*(1-r2)**2,dy=-6*(z[1]-cy)/ry**2*(1-r2)**2;
            for(let k=0;k<3;k++)p[k+5]+=grade.depth*nz*(dx*u[k]+dy*v[k]);
          }
          const length=Math.hypot(...p.slice(5,8));for(let k=5;k<8;k++)p[k]/=length;
          return p;
        }
        for(let a=0;a<steps;a++)for(let b=0;b<steps-a;b++){
          patch.push(...vertex(a,b),...vertex(a+1,b),...vertex(a,b+1));
          if(a+b<steps-1)patch.push(...vertex(a+1,b),...vertex(a+1,b+1),...vertex(a,b+1));
        }
        surfacePatches.push({triangle:index,part:part.name,vertices:patch});
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
  if(open){
    for(const [x,y] of outline){
      const px=cx+x*cutRx,py=cy+y*cutRy;
      const z=sample(skinTris,px,py);if(!Number.isFinite(z)||z<.2)throw Error('Wound outside same-side skin');skins.push(z-depression(px,py));
    }
    const centreSkin=sample(skinTris,cx,cy);
    if(!Number.isFinite(centreSkin))throw Error('Wound centre outside same-side skin');
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
      const col=mix(model.hair_edge,model.hair_highlight,.18+.08*Math.sin(j*1.7));
      triangle(a,c,b,col,'hair_break');triangle(a,d,c,col,'hair_break');
      // Attached remnants hang over the cavity. They originate at surviving hair,
      // have uneven blunt/frayed ends, and are not particles or detached fragments.
      if((style==='bite'?[.14,.28,.39,.61,.75,.89].some(v=>j===Math.floor(v*N)):j%(kind==='dent'?6:4)===0)&&(kind!=='tear'||Math.abs(Math.sin(j*2*Math.PI/N))>.45)){
        const angle=j*2*Math.PI/N,down=Math.sin(angle)>.1;
        const length=style==='bite'?.09+(down?.06:0):kind==='dent'?.24:.18+(down?.18:0);
        const tip=p(j,1-length,hairs[j]-(kind==='dent'?.32:.08));
        const base=p(j,1.055,hairs[j]+.02),side=p(k,1.055,hairs[k]+.02);
        const end=tip.map((q,i)=>q+u[i]*(.06));
        const material=kind==='dent'?'folded_lock':'torn_lock';
        triangle(base,tip,side,col,material);triangle(side,tip,end,col,material);
      }
    }
    const controls=core?baseRecipe.core_cavity.core_lip:baseRecipe.core_cavity.ordinary_lip.map(([scale,depth,t,col],index)=>
      [scale,index<3?depth:depth*grade.depth,t,col]);
    const stepsFor=core?baseRecipe.core_cavity.core_steps:controls.slice(0,-1).map(()=>1);
    const levels=[];
    for(let r=0;r<controls.length-1;r++){
      const a=controls[r],b=controls[r+1],steps=stepsFor[r];
      for(let j=0;j<steps;j++){
        const t=j/steps,depth=a[1]*(1-t)+b[1]*t,raw=mix(a[3],b[3],t);
        levels.push([a[0]*(1-t)+b[0]*t,depth,a[2]*(1-t)+b[2]*t,raw]);
      }
    }
    levels.push(core?[...controls.at(-1).slice(0,3),[...recipe.filling_material.base]]:controls.at(-1));
    const corePresent=core;
    function fillingJoin(x,y){
      // Ordinary filling remains attached around the dense central region.
      // End the inner wall on its flank, instead of a dark moat behind a sphere.
      const a=Math.atan2(y,x),dir=u.map((q,i)=>q*Math.cos(a)+v[i]*Math.sin(a));
      const off=n.map(q=>q*.12),axes=recipe.core_radius;
      const A=dir.reduce((sum,q,i)=>sum+(q/axes[i])**2,0);
      const B=2*dir.reduce((sum,q,i)=>sum+q*off[i]/axes[i]**2,0);
      const C=off.reduce((sum,q,i)=>sum+(q/axes[i])**2,0)-1;
      const r=(-B+Math.sqrt(B*B-4*A*C))/(2*A)*.975;
      return origin.map((q,i)=>q+off[i]+dir[i]*r);
    }
    const rings=levels.map(([scale,depth,t])=>outline.map(([x,y],j)=>{
      const centre=core?t:0,flatten=core?0:t;
      const surface=core&&depth>0?skins[j]*(1-depth/5.7)-1.5*depth/5.7:skins[j]*(1-flatten)+centreSkin*flatten-depth;
      const p=world(cx*(1-centre)+x*cutRx*scale,cy*(1-centre)+y*cutRy*scale,surface);
      if(!corePresent||depth<=3)return p;
      const blend=Math.min(1,(depth-3)/2.7);
      return mix(p,fillingJoin(x,y),blend);
    }));
    for(let r=0;r<rings.length-1;r++)for(let j=0;j<N;j++){
      const k=(j+1)%N,a=rings[r][j],b=rings[r][k],c=rings[r+1][k],d=rings[r+1][j];
      const tone=wallTone(r,j,outline[j][1],levels[r][0]);
      const col=levels[r][3].map(q=>q*tone),mat=r<2?'dough':'filling';
      triangle(a,b,c,col,mat);triangle(a,c,d,col,mat);
    }
    // Only intact exposed filling. No broken-core or extracted-empty variants.
    if(core){
      const existing=createCavity(vertices,skin,baseRecipe.core_cavity,false);
      for(let i=0;i<existing.vertices.length;i+=24){const id=-1-existing.vertices[i+3];if(existing.materials[id]!=='core')continue;
        const points=[0,8,16].map(j=>existing.vertices.slice(i+j,i+j+3));
        // Same bean filling as the cavity: no purple hue, painted gleam or shell.
        const colorBase=recipe.filling_material.core_color||recipe.filling_material.base;
        const grain=[.94,1,1.06][Math.floor(i/24)%3];
        const color=colorBase.map(c=>c*recipe.filling_material.core_density_tint*grain);
        triangle(...points,color,'core');
      }
    }
  }
  const cuttable=new Set([skin.name,...hair.map(p=>p.name),...parts.filter(p=>p.name.startsWith('face_')).map(p=>p.name)]);
  function inside(x,y,z,partName){
    if(!open||!cuttable.has(partName))return false;
    const q=local([x,y,z]);if(q[2]<.15)return false;
    const a=(q[0]-cx)/cutRx,b=(q[1]-cy)/cutRy;
    return insidePolygon(model.hair_parts.includes(partName)?hairOutline:outline,a,b);
  }
  // Flat face normals retain the pixel/low-poly cross-section. The body itself
  // and all unchanged organs keep their existing shading.
  return {vertices:out,colors,materials,surfaceEdits,surfacePatches,inside,cuttable,paintable:new Set([skin.name,...hair.map(p=>p.name),...parts.filter(p=>p.name.startsWith('face_')).map(p=>p.name)]),
    project:p=>{const q=local(p);return [(q[0]-cx)/rx,(q[1]-cy)/ry,q[2]];},
    profile:{origin,u,v,n,center:[cx,cy],cause:spec.cause,radius:radii,cutRadius:[cutRx,cutRy],outline,hairOutline,kind,style,severity,open,core,coreCenter:recipe.core_center,
      hairResponse:recipe.hair_response[style],preserveHairOutsideBreak:true,transparentExterior:true,surfacePatchRule:'Replace listed original triangles with UV-preserving shared-field patches; never draw a skin-colored backing over face organs.'},
    hairSamples:hairs.filter(q=>q!==null).length};
}
module.exports={createHairWound};
