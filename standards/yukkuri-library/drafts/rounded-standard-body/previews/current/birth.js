/* Rounded-body adapter for the existing seekable viviparous choreography.
 * All meshes come from this draft. The opening is carved from actual maternal
 * triangles; an annulus follows the current skin and clips the emerging child.
 */
const RoundedBirth=(()=>{
 const smooth=x=>{x=Math.max(0,Math.min(1,x));return x*x*(3-2*x);};
 const lerp=(a,b,q)=>a+(b-a)*q;
 const api=globalThis.YukkuriViviparous;
 function sample(model,time,count=model.birth.viviparous.offspring_count.preview_default){
  const s=api.sample(model.birth,time,1,count);
  for(const [i,c] of s.children.entries()){
   c.species=model.id;c.id=model.id+'-child-'+c.id.split('-').at(-1);
   if(c.born)c.position[0]+=(i-(count-1)/2)*(model.birth.viviparous.newborn_spacing-3.4)*smooth((s.time-c.birthTime)/model.birth.viviparous.preview_seconds.landing);
  }
  return s;
 }
 function normal(out,a,b,c){
  const u=b.slice(0,3).map((v,k)=>v-a[k]),v=c.slice(0,3).map((v,k)=>v-a[k]);
  const n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]],l=Math.hypot(...n);
  if(l<1e-10)return;
  for(const p of [a,b,c])out.push(...p.slice(0,5),...n.map(v=>v/l));
 }
 function polygon(out,p){for(let i=1;i+1<p.length;i++)normal(out,p[0],p[i],p[i+1]);}
 function clip(poly,dist){
  if(!poly.length)return [];
  let a=poly.at(-1),da=dist(a);const out=[];
  for(const b of poly){const db=dist(b);if((da>=0)!=(db>=0)){const q=da/(da-db);out.push(a.map((v,k)=>lerp(v,b[k],q)));}if(db>=0)out.push(b);a=b;da=db;}
  return out;
 }
 const tri=(v,i)=>[v.slice(i,i+8),v.slice(i+8,i+16),v.slice(i+16,i+24)];
 function skinZ(model,state,y){
  const rows=model.birth.profile.map(p=>api.motherPoint(model.birth,state,[0,p[0],-p[3]]));
  for(let i=1;i<rows.length;i++)if(y<=rows[i][1]){const a=rows[i-1],b=rows[i];return lerp(a[2],b[2],Math.max(0,Math.min(1,(y-a[1])/(b[1]-a[1]))));}
  return rows.at(-1)[2];
 }
 function maternalGeometry(model,state,bodyOnly=false){
  const parts=model.parts.filter(p=>!p.anatomy&&(!bodyOnly||p.name==='rounded_body_surface'||p.name==='rounded_rump_mark'||p.name.startsWith('face_')));
  const deform=vertices=>api.deform(vertices,p=>api.motherPoint(model.birth,state,p));
  const faceParts=parts.filter(p=>p.name.startsWith('face_'));
  const face=deform(posed({...model,parts:faceParts},'idle',0));
  if(state.belly===0)return {vertices:deform(posed({...model,parts},'idle',0)),face};
  const skin=deform(posed({...model,parts:parts.filter(p=>!p.name.startsWith('face_'))},'idle',0));
  const heights=model.birth.profile.map(p=>api.motherPoint(model.birth,state,[0,p[0],-p[3]])[1]);
  const fitted=[];
  for(let i=0;i<face.length;i+=24){
   let strips=[tri(face,i)];
   // Split at the body's actual ring heights before projecting. This prevents
   // a face triangle from bridging a crease and sinking into the coarse skin.
   for(const y of heights){
    const next=[];
    for(const p of strips){
     if(Math.min(...p.map(v=>v[1]))<y-1e-9&&Math.max(...p.map(v=>v[1]))>y+1e-9)next.push(clip(p,v=>y-v[1]),clip(p,v=>v[1]-y));
     else next.push(p);
    }
    strips=next;
   }
   for(const p of strips)polygon(fitted,p.map(v=>{const out=v.slice();out[2]=skinZ(model,state,v[1])-model.birth.face_clearance;return out;}));
  }
  return {vertices:skin.concat(fitted),face:fitted};
 }
 function geometry(model,time,count=model.birth.viviparous.offspring_count.preview_default,bodyOnly=false){
  const state=sample(model,time,count),data=model.birth;
  let mother=maternalGeometry(model,state,bodyOnly).vertices;
  const front=y=>skinZ(model,state,y)-0.008;
  const plane=state.outlet[2],meshes=[];
  const babySource=posed({...model,parts:model.parts.filter(p=>!p.anatomy&&p.name!=='rounded_rump_mark')},'idle',0);
  for(const child of state.children.filter(c=>c.visible)){
   const vertices=api.deform(babySource,p=>api.childPoint(data,state,child,p));
   meshes.push({child,vertices});
  }
  const active=state.time>=data.viviparous.preview_seconds.labor&&state.time<api.birthTime(data,count-1)+.85;
  let hull=[],hole=[],contact=null;
  for(const m of meshes)if(m.child.emerging){hull=api.crossSection(m.vertices,plane);if(hull.length>2){contact=m.child.id;break;}}
  if(active){
   hole=hull.length>2?hull.map(p=>p.slice()):Array.from({length:24},(_,i)=>[state.outlet[0]+.025*Math.cos(i*Math.PI/12),state.outlet[1]+.27*Math.sin(i*Math.PI/12)]);
   const center=[0,1].map(k=>hole.reduce((a,p)=>a+p[k],0)/hole.length);
   if(hull.length>2)hole=hole.map(p=>{const r=Math.hypot(p[0]-center[0],p[1]-center[1]);return p.map((v,k)=>v+(v-center[k])/Math.max(r,1e-9)*.018);});
   const low=[0,1].map(k=>Math.min(...hole.map(p=>p[k]))),high=[0,1].map(k=>Math.max(...hole.map(p=>p[k])));
   const carved=[];
   for(let i=0;i<mother.length;i+=24){
    const t=tri(mother,i);
    if(t.reduce((s,v)=>s+v[2],0)/3>plane+2||[0,1].some(k=>Math.min(...t.map(p=>p[k]))>high[k]||Math.max(...t.map(p=>p[k]))<low[k])){carved.push(...mother.slice(i,i+24));continue;}
    let rest=t;
    for(let j=0;j<hole.length&&rest.length;j++){
     const a=hole[j],b=hole[(j+1)%hole.length],dist=p=>(b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0]);
     polygon(carved,clip(rest,p=>-dist(p)));rest=clip(rest,dist);
    }
   }
   mother=carved;
   const swatch=(p,ink=false)=>[...p,(ink?111.5:132.5)/256,(ink?111.5:132.5)/256,0,0,-1];
   const inner=hole.map(p=>[...p,front(p[1])-.015]);
   const outer=hole.map(p=>{const dx=p[0]-center[0],dy=p[1]-center[1],r=Math.hypot(dx,dy);const x=p[0]+dx/r*.10,y=p[1]+dy/r*.10;return [x,y,front(y)];});
   for(let i=0;i<hole.length;i++){
    const j=(i+1)%hole.length,a=inner[i],b=inner[j],ao=outer[i],bo=outer[j];
    polygon(mother,[swatch(b),swatch(bo),swatch(ao),swatch(a)]);
    polygon(mother,[swatch([a[0],a[1],a[2]+.18],true),swatch([b[0],b[1],b[2]+.18],true),swatch(b,true),swatch(a,true)]);
    polygon(mother,[swatch([center[0],center[1],front(center[1])+.19],true),swatch([b[0],b[1],b[2]+.18],true),swatch([a[0],a[1],a[2]+.18],true)]);
   }
  }
  const batches=[{kind:'mother',vertices:mother,expression:state.straining?'angry':'neutral'}];
  for(const m of meshes){
   let vertices=m.vertices;
   if(m.child.emerging){vertices=[];for(let i=0;i<m.vertices.length;i+=24)polygon(vertices,clip(tri(m.vertices,i),p=>front(p[1])-p[2]));}
   batches.push({kind:'child',id:m.child.id,vertices,expression:m.child.sleeping?'sleep':'neutral'});
  }
  return {state,batches,hole,contact};
 }
 function render(model,textures,time,count,width,height,az,el,span,options={},bodyOnly=false){
  const scene=geometry(model,time,count,bodyOnly);
  // Stack the existing expression atlases vertically, preserving a shared depth
  // buffer across mother and children and retaining each one's expression.
  const keys=['neutral','angry','sleep'],size=model.texture_size,atlas=new Uint8ClampedArray(size*size*4*keys.length);
  keys.forEach((k,i)=>atlas.set(textures[k]||textures.neutral,i*size*size*4));
  // The rasterizer expects square textures: pack rows into a 3x3 square atlas.
  const packed=new Uint8ClampedArray(size*size*4*9);
  for(let j=0;j<3;j++)for(let y=0;y<size;y++)packed.set(atlas.slice((j*size+y)*size*4,(j*size+y+1)*size*4),((j*size+y)*size*3)*4);
  const vertices=[];
  for(const b of scene.batches){const offset=keys.indexOf(b.expression);for(let i=0;i<b.vertices.length;i+=8){const p=b.vertices.slice(i,i+8);p[3]/=3;p[4]=(p[4]+offset)/3;vertices.push(...p);}}
  return {pixels:renderModel(vertices,packed,width,height,az,el,span,{...options,textureSize:size*3}),scene};
 }
 return {sample,geometry,render,skinZ,maternalGeometry};
})();
if(typeof module!=='undefined')module.exports=RoundedBirth;
