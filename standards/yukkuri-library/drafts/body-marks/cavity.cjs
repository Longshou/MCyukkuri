/* Low-poly cavity geometry: a thin dough lip, stepped species-filling walls and ONE
 * central filling body. The actual front skin is sampled to sew the lip to the
 * model. No runtime resources or Java are changed by this authoring module.
 */
'use strict';
const mix=(a,b,t)=>a.map((v,i)=>v*(1-t)+b[i]*t);

function expandOutline(points,count){
  if(points.length>=count)return points.map(p=>[...p]);
  const edges=points.map((p,i)=>({a:p,b:points[(i+1)%points.length],steps:1}));
  let total=points.length;
  while(total<count){
    let best=edges[0],score=-1;
    for(const edge of edges){
      const value=Math.hypot(edge.b[0]-edge.a[0],edge.b[1]-edge.a[1])/edge.steps;
      if(value>score){best=edge;score=value;}
    }
    best.steps++;total++;
  }
  const out=[];
  for(const edge of edges)for(let step=0;step<edge.steps;step++){
    const t=step/edge.steps;out.push(mix(edge.a,edge.b,t));
  }
  return out;
}
function insidePolygon(polygon,x,y){
  let inside=false;
  for(let i=0,j=polygon.length-1;i<polygon.length;j=i++){
    const a=polygon[i],b=polygon[j];
    if((a[1]>y)!==(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])inside=!inside;
  }
  return inside;
}
function wallTone(ring,segment,y,scale){
  const top=Math.max(0,Math.min(1,(y+1)*.5));
  const occlusion=1-.24*top*Math.min(1,scale*1.15);
  const grain=ring<2?.98+.02*((segment+ring)%3):.92+.08*((segment*7+ring*3)%3);
  return occlusion*grain;
}
function createCavity(vertices,skin,spec,broken=false){
  const colors=[],materials=[],out=[];
  const [cx,cy]=spec.opening_center,[rx,ry]=spec.opening_radius;
  const center=spec.core_center,axes=spec.core_radius;
  const outline=expandOutline(spec.outline,spec.segments),N=outline.length;
  function inside(x,y,z){
    return z<center[2]-.25&&insidePolygon(outline,(x-cx)/rx,(y-cy)/ry);
  }
  function front(x,y){
    let found=Infinity;
    for(let t=skin.start*8;t<(skin.start+skin.count)*8;t+=24){
      const ax=vertices[t],ay=vertices[t+1],bx=vertices[t+8],by=vertices[t+9],dx=vertices[t+16],dy=vertices[t+17];
      const det=(by-dy)*(ax-dx)+(dx-bx)*(ay-dy);
      if(Math.abs(det)<1e-8)continue;
      const a=((by-dy)*(x-dx)+(dx-bx)*(y-dy))/det,b=((dy-ay)*(x-dx)+(ax-dx)*(y-dy))/det,c=1-a-b;
      if(Math.min(a,b,c)<-1e-6)continue;
      const z=a*vertices[t+2]+b*vertices[t+10]+c*vertices[t+18];
      found=Math.min(found,z);
    }
    if(!Number.isFinite(found))throw Error('Cavity lip lies outside the body: '+[x,y]);
    return found;
  }
  function triangle(a,b,c,col,kind='filling'){
    const ab=b.map((v,i)=>v-a[i]),ac=c.map((v,i)=>v-a[i]);
    const n=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]];
    const len=Math.hypot(...n);if(len<1e-8)return;
    const material=colors.length;colors.push(col);materials.push(kind);
    for(const p of [a,b,c])out.push(...p,-material-1,0,...n.map(v=>v/len));
  }
  // Few broad, flat bands make the cross-section legible at Minecraft scale.
  // The outer two bands are only the actual dough thickness, not a white frame.
  const controls=spec.core_lip,levels=[];
  for(let r=0;r<controls.length-1;r++){
    const a=controls[r],b=controls[r+1],steps=spec.core_steps[r]||1;
    for(let j=0;j<steps;j++){
      const t=j/steps,scale=a[0]*(1-t)+b[0]*t,depth=a[1]*(1-t)+b[1]*t,centre=a[2]*(1-t)+b[2]*t;
      const raw=mix(a[3],b[3],t);
      levels.push([scale,depth,centre,raw]);
    }
  }
  levels.push([...controls.at(-1).slice(0,3),[...spec.filling_color]]);
  const rings=levels.map(([scale,depth,t])=>outline.map(([ox,oy])=>{
    const x=cx+rx*ox,y=cy+ry*oy;
    const xx=cx*(1-t)+center[0]*t+rx*ox*scale;
    const yy=cy*(1-t)+center[1]*t+ry*oy*scale;
    return [xx,yy,front(x,y)+depth];
  }));
  for(let r=0;r<rings.length-1;r++)for(let j=0;j<N;j++){
    const k=(j+1)%N,a=rings[r][j],b=rings[r][k],c=rings[r+1][k],d=rings[r+1][j];
    const tone=wallTone(r,j,outline[j][1],levels[r][0]);
    const col=levels[r][3].map(v=>v*tone),kind=r<2?'dough':'filling';
    // Facing the cavity mouth, with real wall depth and parallax.
    triangle(a,b,c,col,kind);triangle(a,c,d,col,kind);
  }
  const L=10,M=20;
  function corePoint(i,j){
    const a=j*2*Math.PI/M,t=-Math.PI/2+i*Math.PI/L;
    const k=1+.018*Math.sin(3*a+1)*Math.cos(t)*Math.cos(t);
    return [center[0]+axes[0]*Math.cos(t)*Math.cos(a)*k,
      center[1]+axes[1]*Math.sin(t),center[2]+axes[2]*Math.cos(t)*Math.sin(a)*k];
  }
  for(let i=0;i<L;i++)for(let j=0;j<M;j++){
    const a=corePoint(i,j),b=corePoint(i,j+1),c=corePoint(i+1,j+1),d=corePoint(i+1,j);
    const x=(a[0]+b[0]+c[0]+d[0])/4-center[0],y=(a[1]+b[1]+c[1]+d[1])/4-center[1];
    const z=(a[2]+b[2]+c[2]+d[2])/4-center[2];
    const fleck=[.94,1,1.06][(i*7+j*3)%3];
    let col=(spec.core_color||spec.filling_color).map(v=>v*fleck);
    const cut=Math.abs(y-.18*Math.sin(x*7)-.12*x)<.055;
    const branch=x>.05&&Math.abs(y+.55*x+.12)<.045;
    if(broken&&z<0&&(cut||branch))col=[48,32,28];
    // Flat source normals keep the central filling in the same low-poly language.
    triangle(a,b,c,col,'core');triangle(a,c,d,col,'core');
  }
  return {vertices:out,colors,materials,inside,coreCenter:center,levels,outline};
}
module.exports={createCavity,expandOutline,insidePolygon,wallTone};
