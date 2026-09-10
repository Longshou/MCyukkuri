/* Seekable delivery choreography. Birth events belong to create(), never geometry(). */
(function(root){
 'use strict';
 const clamp=x=>Math.max(0,Math.min(1,x)),smooth=x=>{x=clamp(x);return x*x*(3-2*x);};
 const lerp=(a,b,t)=>a+(b-a)*t;
 function validateCount(data,count){const r=data.viviparous.offspring_count;if(!Number.isInteger(count)||count<r.min||count>r.max)throw new RangeError('Invalid litter count');return count;}
 function randomCount(data,random=Math.random){const r=data.viviparous.offspring_count,q=random();if(!Number.isFinite(q)||q<0||q>=1)throw new RangeError('Invalid random draw');return r.min+Math.floor(q*(r.max-r.min+1));}
 function startTime(data,i){const t=data.viviparous.preview_seconds;return t.first_start+i*t.interval;}
 function birthTime(data,i){return startTime(data,i)+data.viviparous.preview_seconds.emergence;}
 function motherPoint(data,state,p){
  const cfg=data.viviparous.pregnant_shape,[x,y,z]=p,b=state.belly,w=1-smooth(y/cfg.transition_y),press=state.contraction;
  return [x*(1+cfg.lower_width*b*w)*(1+.035*press),
   (y+cfg.lower_height*b*smooth(y/cfg.transition_y))*(1-.04*press),
   z*(1+cfg.lower_depth*b*w)].map(v=>v*state.motherScale);
 }
 function sample(data,seconds,motherScale=1,count=data.viviparous.offspring_count.preview_default){
  validateCount(data,count);
  const adult=root.YukkuriGrowth.stage(data.growth,'adult');
  if(!Number.isFinite(seconds)||!Number.isFinite(motherScale)||motherScale<adult.min_scale||motherScale>adult.max_scale)throw new RangeError('Invalid time or adult scale');
  const cfg=data.viviparous,t=cfg.preview_seconds,time=Math.max(0,Math.min(t.end,seconds));
  const last=birthTime(data,count-1),delivery=Array.from({length:count},(_,i)=>smooth((time-startTime(data,i))/t.emergence));
  const progress=delivery.reduce((a,b)=>a+b,0)/count;
  const belly=smooth(time/t.pregnant)*(1-.78*progress)*(1-smooth((time-last)/t.recovery));
  const labor=smooth((time-t.labor)/1.1)*(1-smooth((time-last)/.5));
  const contraction=labor*(.22+.78*Math.pow(.5+.5*Math.sin((time-t.labor)*Math.PI*2/2.4),4));
  const state={time,count,motherScale,belly,contraction,straining:contraction>.35};
  state.outlet=motherPoint(data,state,cfg.birth_outlet.anchor);
  state.opening=Math.max(0,...delivery.map((u,i)=>Math.sin(Math.PI*smooth((time-startTime(data,i)+.45)/(t.emergence+.9)))));
  if(time<t.first_start-.45||time>last+.45)state.opening=0;
  const children=Array.from({length:count},(_,i)=>{
   const start=startTime(data,i),bornAt=birthTime(data,i),u=clamp((time-start)/t.emergence),born=time>=bornAt;
   const scale=cfg.birth_scale,groundX=(i-(count-1)/2)*3.4,groundZ=-11.8-(i%2)*1.6;
   // Use a frozen outlet at complete emergence so the newborn's path cannot follow later contractions.
   const anchor=state.outlet;
   const poseForExit=()=>{
    const fraction=(i+1)/count;
    const frozen={motherScale,belly:1-.78*fraction,contraction:(.22+.78*Math.pow(.5+.5*Math.sin((bornAt-t.labor)*Math.PI*2/2.4),4))};
    return motherPoint(data,frozen,cfg.birth_outlet.anchor);
   };
   const exit=poseForExit();
   const emergencePos=[anchor[0],Math.max(.12,anchor[1]-.5*data.body_height*scale),anchor[2]+lerp(1.5,-2.0,smooth(u))];
   let position=emergencePos,landed=false;
   if(born){const f=clamp((time-bornAt)/t.landing),k=smooth(f),height=Math.max(.12,exit[1]-.5*data.body_height*scale);position=[lerp(exit[0],groundX,k),height*(1-f*f),lerp(exit[2]-2,groundZ,k)];landed=f>=1;}
   return {id:'reimu-viviparous-child-'+(i+1),species:'reimu',stage:cfg.birth_stage,scale,visible:time>=start,born,landed,sleeping:!born,emerging:time>=start&&!born,progress:u,position,squeeze:born?1:lerp(.72,1,smooth(u)),birthTime:bornAt};
  });
  const born=children.filter(c=>c.born).length;
  return Object.assign(state,{children,born,complete:time===t.end,phase:time<t.pregnant?'孕体变化':time<t.labor?'临产待生':time<t.first_start?'阵痛用力':time<last?'依次产出':time<last+t.recovery?'产后恢复':'完成'});
 }
 function deform(vertices,map){
  const out=vertices.slice();
  for(let i=0;i<out.length;i+=8){const p=map(vertices.slice(i,i+3));out[i]=p[0];out[i+1]=p[1];out[i+2]=p[2];}
  for(let i=0;i<out.length;i+=24){const a=[out[i+8]-out[i],out[i+9]-out[i+1],out[i+10]-out[i+2]],b=[out[i+16]-out[i],out[i+17]-out[i+1],out[i+18]-out[i+2]];let n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],len=Math.hypot(...n);if(len<1e-12)continue;const sign=n.reduce((s,v,j)=>s+v*vertices[i+5+j],0)<0?-1:1;n=n.map(v=>v/len*sign);for(let k=0;k<3;k++)for(let j=0;j<3;j++)out[i+k*8+5+j]=n[j];}
  return out;
 }
 function surfaceVisibility(data,state){const t=data.viviparous.preview_seconds;return smooth((state.time-t.labor)/.65)*(1-smooth((state.time-birthTime(data,state.count-1))/.85));}
 function childPoint(data,state,child,p){
  const cfg=data.viviparous.outlet_surface,scale=child.scale,z=p[2]*scale+child.position[2];
  // A narrow contact band squeezes only the section inside the rim. Released
  // material regains its width; the entire newborn is never scaled down.
  const passage=smooth(child.progress/.12)*(1-smooth((child.progress-.78)/.22));
  const pressure=child.emerging?passage*Math.exp(-Math.pow((z-state.outlet[2]+.06)/cfg.contact_band,2)):0;
  const cy=data.body_height*.5,nx=Math.min(1,Math.abs(p[0]/6)),ny=Math.min(1,Math.abs((p[1]-cy)/cy));
  const sx=lerp(1,cfg.compression[0],pressure)*Math.sqrt(1-.32*ny*ny*pressure);
  const sy=lerp(1,cfg.compression[1],pressure)*Math.sqrt(1-.32*nx*nx*pressure);
  return [p[0]*scale*sx+child.position[0],((p[1]-cy)*sy+cy)*scale+child.position[1],z];
 }
 function childGeometry(data,state,child){
  const v=child.sleeping?(data.child_sleep_vertices||data.sleep_vertices).concat(root.YukkuriStem.sleepFace(data.sleep)):data.vertices;
  return deform(v,p=>childPoint(data,state,child,p));
 }
 function crossSection(vertices,z){
  const points=[];
  for(let k=0;k<vertices.length;k+=24)for(let j=0;j<3;j++){
   const a=k+j*8,b=k+(j+1)%3*8,za=vertices[a+2],zb=vertices[b+2];
   if(Math.abs(za-z)<1e-8)points.push([vertices[a],vertices[a+1]]);
   if((za<z&&zb>z)||(za>z&&zb<z)){const u=(z-za)/(zb-za);points.push([lerp(vertices[a],vertices[b],u),lerp(vertices[a+1],vertices[b+1],u)]);}
  }
  const sorted=points.sort((a,b)=>a[0]-b[0]||a[1]-b[1]).filter((p,i,a)=>!i||Math.hypot(p[0]-a[i-1][0],p[1]-a[i-1][1])>1e-7);
  const cross=(a,b,c)=>(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
  const half=arr=>{const h=[];for(const p of arr){while(h.length>1&&cross(h[h.length-2],h[h.length-1],p)<=0)h.pop();h.push(p);}return h;};
  if(sorted.length<3)return [];
  const lower=half(sorted),upper=half(sorted.slice().reverse());return lower.slice(0,-1).concat(upper.slice(0,-1));
 }
 function outletSurface(data,state,meshes){
  const cfg=data.viviparous.outlet_surface,visibility=surfaceVisibility(data,state),plane=state.outlet[2]-.06;
  let hull=[],contact=null,influence=0;
  for(const entry of meshes)if(entry.child.emerging){
   let low=Infinity,high=-Infinity;for(let k=2;k<entry.vertices.length;k+=8){low=Math.min(low,entry.vertices[k]);high=Math.max(high,entry.vertices[k]);}
   const distance=Math.max(low-plane,plane-high,0),weight=1-smooth(distance/.45);
   const h=weight>0?crossSection(entry.vertices,Math.max(low+.0001,Math.min(high-.0001,plane))):[];
   if(h.length>2){hull=h;influence=weight;contact=distance===0?entry.child.id:null;break;}
  }
  let center=state.outlet.slice(0,2);
  let hullCenter=center;
  if(hull.length){hullCenter=[0,1].map(k=>(Math.min(...hull.map(p=>p[k]))+Math.max(...hull.map(p=>p[k])))/2);center=center.map((v,k)=>lerp(v,hullCenter[k],influence));}
  const positive=a=>(a+Math.PI*2)%(Math.PI*2),patch=cfg.patch,cy=data.viviparous.birth_outlet.anchor[1];
  const angles=Array.from({length:cfg.segments},(_,i)=>i/cfg.segments*Math.PI*2);
  for(const x of [-patch.half_width,patch.half_width])for(const y of [-cy,patch.top-cy])angles.push(positive(Math.atan2(y,x)));
  for(const x of [-patch.half_width,patch.half_width])for(let y=.265;y<patch.top;y+=.265)angles.push(positive(Math.atan2(y-cy,x)));
  for(const p of hull)angles.push(positive(Math.atan2(p[1]-hullCenter[1],p[0]-hullCenter[0])));
  angles.sort((a,b)=>a-b);const unique=angles.filter((a,i)=>!i||a-angles[i-1]>1e-7);
  const inner=[];
  for(const angle of unique){
   const dx=Math.cos(angle),dy=Math.sin(angle);
   const closed=1/Math.hypot(dx/(.025*state.motherScale),dy/(.27*state.motherScale));let r=closed;
   if(hull.length){r=Infinity;for(let j=0;j<hull.length;j++){
    const a=hull[j],b=hull[(j+1)%hull.length],ex=b[0]-a[0],ey=b[1]-a[1],den=dx*ey-dy*ex;
    if(Math.abs(den)<1e-10)continue;
    const ax=a[0]-hullCenter[0],ay=a[1]-hullCenter[1],distance=(ax*ey-ay*ex)/den,u=(ax*dy-ay*dx)/den;
    if(distance>=0&&u>=-1e-7&&u<=1+1e-7)r=Math.min(r,distance);
   }r=lerp(closed,r+.012,influence);}
   inner.push([center[0]+dx*r,center[1]+dy*r,plane]);
  }
  return {inner,center,plane,visibility,contact,hull,influence,angles:unique};
 }
 function triangle(out,a,b,c,color){
  const u=b.map((v,k)=>v-a[k]),v=c.map((v,k)=>v-a[k]);let normal=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]],length=Math.hypot(...normal);
  if(length<1e-10)return;normal=normal.map(v=>v/length);
  for(const p of [a,b,c])out.push(...p,-1-color,0,...normal);
 }
 function surfaceGeometry(data,state,surface){
  const cfg=data.viviparous.outlet_surface,n=surface.inner.length,out=[],s=state.motherScale;
  const loops=[],width=cfg.rim_width*s*surface.visibility,depth=cfg.rim_depth*s*surface.visibility;
  // Continuous rings share vertices, including the last/first segment.
  for(const [offset,dz] of [[0,.27*s],[0,0],[.18*width,-.75*depth],[.5*width,-depth],[width,-.35*depth],[1.7*width,.012]]){
   loops.push(surface.inner.map((p,i)=>{const a=surface.angles[i];return [p[0]+offset*Math.cos(a),p[1]+offset*Math.sin(a),p[2]+dz];}));
  }
  const patch=cfg.patch,[cx,cy]=data.viviparous.birth_outlet.anchor;
  const outer=surface.inner.map((p,i)=>{const angle=surface.angles[i],dx=Math.cos(angle),dy=Math.sin(angle),r=Math.min(patch.half_width/Math.max(1e-8,Math.abs(dx)),(dy>0?patch.top-cy:cy)/Math.max(1e-8,Math.abs(dy)));return motherPoint(data,state,[cx+r*dx,cy+r*dy,-4.6]);});
  // Follow the curved lower face between rim and patch boundary. A single
  // triangle fan would bridge across and cover the mouth above the outlet.
  const skinZ=y=>{let a=0,b=patch.top;for(let i=0;i<18;i++){const mid=(a+b)/2;if(motherPoint(data,state,[0,mid,-4.6])[1]<y)a=mid;else b=mid;}return motherPoint(data,state,[0,(a+b)/2,-4.6])[2];};
  const lip=loops[loops.length-1];
  for(let j=1;j<=10;j++){
   const u=j/10;
   loops.push(lip.map((p,i)=>{const q=outer[i],x=lerp(p[0],q[0],u),y=lerp(p[1],q[1],u),z=skinZ(y)+(p[2]-skinZ(p[1]))*(1-smooth(u/.35));return [x,y,j===10?q[2]:z];}));
  }
  // The annulus replaces the carved face, so a newborn can actually occupy
  // the opening instead of intersecting an opaque body surface.
  for(let j=0;j<loops.length-1;j++)for(let i=0;i<n;i++){
   const next=(i+1)%n,color=j<=1?cfg.crease_color:j===2?cfg.inner_color:cfg.skin_color;
   triangle(out,loops[j][i],loops[j][next],loops[j+1][next],color);
   triangle(out,loops[j][i],loops[j+1][next],loops[j+1][i],color);
  }
  const cap=[surface.center[0],surface.center[1],surface.plane+.29*s];
  for(let i=0;i<n;i++)triangle(out,cap,loops[0][(i+1)%n],loops[0][i],cfg.crease_color);
  return out;
 }
 function geometry(data,state){
  const cfg=data.viviparous,stem=root.YukkuriStem;
  const meshes=state.children.filter(c=>c.visible).map(child=>({child,vertices:childGeometry(data,state,child)}));
  const surface=outletSurface(data,state,meshes),active=surface.visibility>.001;
  const mother=active?(state.straining?data.delivery_effort_vertices:data.delivery_vertices):(state.straining?data.effort_vertices:data.vertices);
  let out=deform(mother,p=>motherPoint(data,state,p));
  if(active)out=out.concat(surfaceGeometry(data,state,surface));
  for(const mesh of meshes)out=out.concat(mesh.vertices);
  for(let x=-15;x<=15;x+=3)stem.rod(out,[x,-.12,-18],[x,-.12,12],.015,3);
  for(let z=-18;z<=12;z+=3)stem.rod(out,[-15,-.12,z],[15,-.12,z],.015,3);
  return out;
 }
 function create(data,mother={},options={}){
  const identity={id:'reimu-mother',species:'reimu',stage:'adult',ecology:'ordinary',reproductionMode:'viviparous',scale:1,...mother};
  if(identity.species!==data.viviparous.character||identity.stage!=='adult'||identity.ecology!=='ordinary'||identity.reproductionMode!=='viviparous')throw new Error('Requires ordinary adult Reimu with viviparous reproduction');
  const count=validateCount(data,options.count??randomCount(data,options.random));sample(data,0,identity.scale,count);
  let seconds=0,emitted=new Set();
  return {state(){return sample(data,seconds,identity.scale,count);},advance(dt){
   if(!Number.isFinite(dt)||dt<0)throw new RangeError('Invalid delta');seconds=Math.min(data.viviparous.preview_seconds.end,seconds+dt);
   const state=this.state(),events=[];for(const child of state.children)if(child.born&&!emitted.has(child.id)){emitted.add(child.id);events.push({type:'birth',childId:identity.id+':'+child.id,motherId:identity.id,species:child.species,stage:child.stage,scale:child.scale});}return {state,events};
  },save(){return {version:1,behavior:data.viviparous.id,mother:{...identity},count,seconds,emitted:[...emitted]};},restore(saved){
   if(saved.version!==1||saved.behavior!==data.viviparous.id||saved.count!==count||JSON.stringify(saved.mother)!==JSON.stringify(identity)||!Number.isFinite(saved.seconds)||saved.seconds<0||saved.seconds>data.viviparous.preview_seconds.end||!Array.isArray(saved.emitted))throw new Error('Invalid viviparous snapshot');
   const born=sample(data,saved.seconds,identity.scale,count).children.filter(c=>c.born).map(c=>c.id);
   if(new Set(saved.emitted).size!==saved.emitted.length||saved.emitted.length!==born.length||saved.emitted.some(id=>!born.includes(id)))throw new Error('Snapshot birth events mismatch');seconds=saved.seconds;emitted=new Set(saved.emitted);
  }};
 }
 const api={sample,geometry,create,motherPoint,deform,childPoint,childGeometry,crossSection,outletSurface,surfaceGeometry,startTime,birthTime,randomCount,validateCount};root.YukkuriViviparous=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
