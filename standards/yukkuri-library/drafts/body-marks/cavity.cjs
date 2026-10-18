/* Pending art geometry: a large opening, recessed filling walls and ONE central
 * filling body. The actual front skin is sampled to sew the lip to the model.
 * No runtime resources or Java are changed by this authoring module.
 */
'use strict';
function createCavity(vertices, skin, spec, broken=false){
  const colors=[],materials=[],out=[];
  const [cx,cy]=spec.opening_center,[rx,ry]=spec.opening_radius;
  const center=spec.core_center,axes=spec.core_radius,N=spec.segments;
  const rough=a=>1+.045*Math.sin(a*3+.4)+.027*Math.sin(a*7+1.1)+.016*Math.sin(a*13+.8);
  function inside(x,y,z){
    const u=(x-cx)/rx,v=(y-cy)/ry,a=Math.atan2(v,u);
    return z<center[2]-.25&&Math.hypot(u,v)<rough(a);
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
  // ring scale, inward translation, centre interpolation, material, darkness.
  // The far rings are ordinary filling; the separate core sits in front of them.
  const controls=[
    [1.045,-.035,0,[234,209,163]],
    [1.0,.01,0,[246,221,178]],
    [.947,.22,0,[215,183,144]],
    [.86,.93,.16,[111,73,58]],
    [.72,1.95,.40,[91,58,49]],
    [.53,3.40,.73,[72,44,40]],
    [.32,4.70,1,[55,33,32]],
    [0,5.6,1,[51,34,32]],
  ];
  const levels=[];
  for(let r=0;r<controls.length-1;r++){
    const a=controls[r],b=controls[r+1],steps=r<2?1:5;
    for(let j=0;j<steps;j++){
      const t=j/steps;
      levels.push([a[0]*(1-t)+b[0]*t,a[1]*(1-t)+b[1]*t,a[2]*(1-t)+b[2]*t,
        a[3].map((v,k)=>v*(1-t)+b[3][k]*t)]);
    }
  }
  levels.push(controls.at(-1));
  const rings=levels.map(([scale,depth,t])=>Array.from({length:N},(_,j)=>{
    const a=j*2*Math.PI/N,k=rough(a),x=cx+rx*k*Math.cos(a),y=cy+ry*k*Math.sin(a);
    const xx=cx*(1-t)+center[0]*t+rx*k*Math.cos(a)*scale;
    const yy=cy*(1-t)+center[1]*t+ry*k*Math.sin(a)*scale;
    return [xx,yy,front(x,y)+depth];
  }));
  for(let r=0;r<rings.length-1;r++)for(let j=0;j<N;j++){
    const k=(j+1)%N,a=rings[r][j],b=rings[r][k],c=rings[r+1][k],d=rings[r+1][j];
    const col=levels[r][3];
    // Facing the cavity mouth, with real wall depth and parallax.
    const kind=r<2?'dough':'filling';
    triangle(a,c,b,col,kind);triangle(a,d,c,col,kind);
  }
  const L=28,M=56;
  function corePoint(i,j){
    const a=j*2*Math.PI/M,t=-Math.PI/2+i*Math.PI/L;
    const k=1+.025*Math.sin(3*a+1)*Math.cos(t)*Math.cos(t);
    return [center[0]+axes[0]*Math.cos(t)*Math.cos(a)*k,
      center[1]+axes[1]*Math.sin(t),center[2]+axes[2]*Math.cos(t)*Math.sin(a)*k];
  }
  for(let i=0;i<L;i++)for(let j=0;j<M;j++){
    const a=corePoint(i,j),b=corePoint(i,j+1),c=corePoint(i+1,j+1),d=corePoint(i+1,j);
    const x=(a[0]+b[0]+c[0]+d[0])/4-center[0],y=(a[1]+b[1]+c[1]+d[1])/4-center[1];
    const z=(a[2]+b[2]+c[2]+d[2])/4-center[2];
    let col=[76,42,46];
    const fleck=.96+.07*Math.sin(i*9.13+j*4.73);
    const gleam=Math.exp(-((x+.35)**2/.12+(y-.35)**2/.05))*Math.max(0,-z/axes[2]);
    col=col.map((v,k)=>v*fleck+[28,23,20][k]*gleam);
    const cut=Math.abs(y-.18*Math.sin(x*7)-.12*x)<.055;
    const branch=x>.05&&Math.abs(y+.55*x+.12)<.045;
    if(broken&&z<0&&(cut||branch))col=[31,21,23];
    triangle(a,c,b,col,'core');triangle(a,d,c,col,'core');
  }
  // Smooth the soft filling and core, retaining the distinct dough lip.
  const groups=new Map(),keys=[];
  for(let i=0;i<out.length;i+=8){
    const kind=materials[-1-out[i+3]];
    const key=kind==='dough'?String(i):kind+':'+out.slice(i,i+3).map(v=>Math.round(v*1e5)).join(',');
    keys.push(key);if(!groups.has(key))groups.set(key,[0,0,0]);
    const sum=groups.get(key);for(let k=0;k<3;k++)sum[k]+=out[i+5+k];
  }
  for(let i=0;i<out.length;i+=8){
    const n=groups.get(keys[i/8]),len=Math.hypot(...n);
    for(let k=0;k<3;k++)out[i+5+k]=n[k]/len;
  }
  return {vertices:out,colors,materials,inside,coreCenter:center};
}
module.exports={createCavity};
