/* Procedural hair pieces for the placement fit: Marisa's single braid and the loose lock opposite it.

   Builds rest-space triangles (position + UV, two-bone weights) that replace whole parts. Shapes follow the
   Hakkei U hair sprites 6203 (braid: stacked plaits narrowing downward, a large white bow near the end and
   a jagged tuft) and 5802 (loose side hair with pointed tips). Colours come from the form's own atlas
   swatches, given in atlas pixels: each plait shades from the light swatch at its widest band to the dark
   swatch at the joints, so the plaits read as separate lobes. Pure geometry; no files. */
'use strict';
const add=(a,b)=>[a[0]+b[0],a[1]+b[1],a[2]+b[2]],scale=(a,s)=>[a[0]*s,a[1]*s,a[2]*s],lerp=(a,b,t)=>a.map((x,k)=>x+(b[k]-x)*t);
const rotY=(p,deg)=>{const r=deg*Math.PI/180,c=Math.cos(r),s=Math.sin(r);return [p[0]*c+p[2]*s,p[1],-p[0]*s+p[2]*c];};
const rotZ=(p,deg)=>{const r=deg*Math.PI/180,c=Math.cos(r),s=Math.sin(r);return [p[0]*c-p[1]*s,p[0]*s+p[1]*c,p[2]];};
const rotX=(p,deg)=>{const r=deg*Math.PI/180,c=Math.cos(r),s=Math.sin(r);return [p[0],p[1]*c-p[2]*s,p[1]*s+p[2]*c];};

/* A ring of n points (octagon by default) in the local XZ plane, radii rx, rz. */
function ring(rx,rz,n=8,phase=Math.PI/8){const out=[];for(let i=0;i<n;i++){const a=phase+i*2*Math.PI/n;out.push([Math.cos(a)*rx,0,Math.sin(a)*rz]);}return out;}

class Mesh{
 constructor(texture){this.tris=[];this.t=texture;}
 uv(px){return [px[0]/this.t,px[1]/this.t];}
 /* One triangle; corners [x,y,z], uvs in atlas pixels, all corners on bone binding w. */
 tri(a,b,c,ua,ub,uc,w){const [A,B,C]=[ua,ub,uc].map(u=>this.uv(u));this.tris.push({v:[[...a,...A],[...b,...B],[...c,...C]],w:[w.slice(),w.slice(),w.slice()]});}
 /* Quad a-b-c-d (counter-clockwise seen from outside). */
 quad(a,b,c,d,ua,ub,uc,ud,w){this.tri(a,b,c,ua,ub,uc,w);this.tri(a,c,d,ua,uc,ud,w);}
 /* Band between two rings (same size), outward-facing; u runs from uTop to uBottom along the band, v across. */
 band(top,bottom,uTop,uBottom,vRange,w){const n=top.length;
  for(let i=0;i<n;i++){const j=(i+1)%n,v0=vRange[0]+(vRange[1]-vRange[0])*i/n,v1=vRange[0]+(vRange[1]-vRange[0])*(i+1)/n;
   this.quad(top[j],top[i],bottom[i],bottom[j],[uTop,v1],[uTop,v0],[uBottom,v0],[uBottom,v1],w);}}
 /* Every primitive is convex: flip any triangle added since `from` whose normal points toward its centre. */
 orient(from,center){for(let i=from;i<this.tris.length;i++){const t=this.tris[i],p=t.v.map(v=>v.slice(0,3)),e1=p[1].map((x,k)=>x-p[0][k]),e2=p[2].map((x,k)=>x-p[0][k]);
  const n=[e1[1]*e2[2]-e1[2]*e2[1],e1[2]*e2[0]-e1[0]*e2[2],e1[0]*e2[1]-e1[1]*e2[0]],c=p.map(q=>q).reduce((a,q)=>a.map((x,k)=>x+q[k]/3),[0,0,0]);
  if(n[0]*(c[0]-center[0])+n[1]*(c[1]-center[1])+n[2]*(c[2]-center[2])<0){[t.v[1],t.v[2]]=[t.v[2],t.v[1]];[t.w[1],t.w[2]]=[t.w[2],t.w[1]];}}}
 /* Fan cap; up=true faces +local-up. */
 cap(r,center,u,v,w,up){const n=r.length;for(let i=0;i<n;i++){const j=(i+1)%n;if(up)this.tri(center,r[j],r[i],[u,v],[u,v],[u,v],w);else this.tri(center,r[i],r[j],[u,v],[u,v],[u,v],w);}}
}

/* Place local points: rotate (x tilt, z tilt, y twist) then translate. */
const place=(pts,at,tx,tz,ty)=>pts.map(p=>add(rotY(rotZ(rotX(p,tx),tz),ty),at));

/* A plait: two frustums meeting at a wide waist (bicone), so stacked plaits leave a notch at every joint. */
function plait(mesh,at,h,rx,rz,tilt,twist,sw,w,end=0.55){
 const waist=ring(rx,rz),narrow=ring(rx*end,rz*end);
 const top=place(narrow.map(p=>add(p,[0,h/2,0])),at,tilt[0],tilt[1],twist),mid=place(waist,at,tilt[0],tilt[1],twist),bot=place(narrow.map(p=>add(p,[0,-h/2,0])),at,tilt[0],tilt[1],twist);
 const [dark,light]=[sw.dark[0]+8,sw.light[0]+4],v=[sw.row[0]+1,sw.row[1]-1],from=mesh.tris.length;
 mesh.band(top,mid,dark,light,v,w);mesh.band(mid,bot,light,dark,v,w);
 const c=(p)=>place([p],at,tilt[0],tilt[1],twist)[0];
 mesh.cap(top,c([0,h/2,0]),sw.dark[0]+8,sw.row[0]+8,w,true);mesh.cap(bot,c([0,-h/2,0]),sw.dark[0]+8,sw.row[0]+8,w,false);
 mesh.orient(from,at);
}

/* A pointed strand: a frustum from a base ring to a sharp tip along dir. */
function spike(mesh,base,dir,len,rx,rz,sw,w,twist=0){
 const n=6,ringPts=place(ring(rx,rz,n,0),[0,0,0],0,0,twist);
 // orient: local -y along dir
 const d=Math.hypot(...dir),u=scale(dir,1/d),up=[0,-1,0];
 const axis=[up[1]*u[2]-up[2]*u[1],up[2]*u[0]-up[0]*u[2],up[0]*u[1]-up[1]*u[0]],s=Math.hypot(...axis),c=up[0]*u[0]+up[1]*u[1]+up[2]*u[2];
 const R=p=>{if(s<1e-9)return c>0?p:[p[0],-p[1],-p[2]];const k=scale(axis,1/s),ang=Math.atan2(s,c),cs=Math.cos(ang),sn=Math.sin(ang),kd=k[0]*p[0]+k[1]*p[1]+k[2]*p[2],kx=[k[1]*p[2]-k[2]*p[1],k[2]*p[0]-k[0]*p[2],k[0]*p[1]-k[1]*p[0]];return p.map((x,i)=>x*cs+kx[i]*sn+k[i]*kd*(1-cs));};
 const ringW=ringPts.map(p=>add(R(p),base)),tip=add(base,scale(u,len)),from=mesh.tris.length;
 for(let i=0;i<n;i++){const j=(i+1)%n;mesh.tri(ringW[j],ringW[i],tip,[sw.light[0]+4,sw.row[0]+2],[sw.light[0]+12,sw.row[0]+2],[sw.mid[0]+8,sw.row[1]-2],w);}
 mesh.cap(ringW,base,sw.mid[0]+8,sw.row[0]+8,w,true);
 mesh.orient(from,add(base,scale(u,len*.25)));
}

/* Bow loop (sprite 6203): a teardrop puff facing -z, fuller at the outer end (out = +1/-1 along its long axis),
   with a recessed shaded fold so it reads as a ribbon loop rather than a disc. */
function loop(mesh,center,len,hgt,thick,angle,sw,w,out=0){
 const n=10,outer=[],inner=[];
 for(let i=0;i<n;i++){const a=i*2*Math.PI/n,x=Math.cos(a),y=Math.sin(a),k=out?1+.28*x*out:1;
  outer.push([x*len/2,y*hgt/2*k,0]);inner.push([x*len/2*.5+out*len*.08,y*hgt/2*k*.45,0]);}
 const T=(p,dz)=>add(rotZ(add(p,[0,0,dz]),angle),center),from=mesh.tris.length;
 const front=outer.map(p=>T(p,-thick/2)),fold=inner.map(p=>T(p,-thick*.12)),back=outer.map(p=>T(p,thick/2));
 const white=[sw.ribbon[0]+8,sw.ribbon[1]+8],shade=[sw.ribbon_shade[0]+8,sw.ribbon_shade[1]+8],cf=T([out*len*.08,0,0],-thick*.12),cb=T([0,0,0],thick/2);
 for(let i=0;i<n;i++){const j=(i+1)%n;
  mesh.quad(front[i],front[j],fold[j],fold[i],white,white,white,white,w);mesh.tri(cf,fold[i],fold[j],shade,shade,shade,w);
  mesh.tri(cb,back[j],back[i],shade,shade,shade,w);mesh.quad(front[j],front[i],back[i],back[j],shade,shade,shade,shade,w);}
 // The fold is a recess: keep its faces turned toward the viewer in front, not outward from the loop centre.
 const c=T([0,0,0],0);for(let i=from;i<mesh.tris.length;i++){const t=mesh.tris[i],q=t.v.map(v=>v.slice(0,3)),e1=q[1].map((x,k)=>x-q[0][k]),e2=q[2].map((x,k)=>x-q[0][k]);
  const nn=[e1[1]*e2[2]-e1[2]*e2[1],e1[2]*e2[0]-e1[0]*e2[2],e1[0]*e2[1]-e1[1]*e2[0]],m=q.reduce((a,p)=>a.map((x,k)=>x+p[k]/3),[0,0,0]);
  const front_=m[2]<c[2]-thick*.05,want=front_?[0,0,-1]:m.map((x,k)=>x-c[k]),wantN=front_?rotZ(want,angle):want;
  if(nn[0]*wantN[0]+nn[1]*wantN[1]+nn[2]*wantN[2]<0){[t.v[1],t.v[2]]=[t.v[2],t.v[1]];[t.w[1],t.w[2]]=[t.w[2],t.w[1]];}}
}

/* Single braid (sprite 6203 and the classic composite): an unplaited gathered section from under the brim,
   then chevron plaits that widen slightly toward the bow, the white two-loop bow turned outward, and a short
   tuft that spreads into points. Returns {lock,tip,ribbon} triangle lists. */
function braid(step,texture,binding){
 const sw=step.swatches,lock=new Mesh(texture),tip=new Mesh(texture),bow=new Mesh(texture);
 const g=step.gather,root=step.root,start=[root[0]+(g.dx||0),g.y,root[2]+(g.dz||0)],end=step.end;
 // Gathered section: a tapered octagonal prism in the main hair colour.
 {const from=lock.tris.length,a=ring(g.width[0]/2,g.depth[0]/2).map(p=>add(p,root)),c=ring(g.width[1]/2,g.depth[1]/2).map(p=>add(p,start)),u=sw.mid[0]+8;
  lock.band(a,c,u,u,[sw.row[0]+1,sw.row[1]-1],binding.upper);lock.cap(a,root,u,sw.row[0]+8,binding.upper,true);lock.cap(c,start,u,sw.row[0]+8,binding.upper,false);
  lock.orient(from,lerp(root,start,.5));}
 // Plaits: each plait is two lobes leaning toward the centre line, neighbours offset by half a plait.
 const n=step.plaits*2,[w0,w1]=step.width,[d0,d1]=step.depth,len=start[1]-end[1],pitch=len/n;
 for(let i=0;i<n;i++){const t=(i+.5)/n,c=lerp(start,end,t),s=i%2?1:-1,width=w0+(w1-w0)*t,depth=d0+(d1-d0)*t;
  const at=add(c,[s*width*step.offset,0,-s*depth*step.offset*.4]),w=c[1]>=step.bones.split?binding.upper:binding.lower;
  plait(lock,at,pitch*step.lump,width*step.radius,depth*(step.radius+.1),[-s*step.tilt[0],-s*step.tilt[1]],s*step.twist,sw,w,step.taper??.5);}
 // Bow: two loops and a knot, turned outward about the vertical axis.
 const b=step.bow,bc=b.center,bw=binding.ribbon,turn=p=>add(rotY(p,b.yaw||0),bc),from=bow.tris.length;
 const loopAt=(dx,angle,len,hgt,thick,out)=>{const m=new Mesh(texture);loop(m,[dx,0,0],len,hgt,thick,angle,sw,bw,out);
  for(const t of m.tris){t.v=t.v.map(v=>[...turn(v.slice(0,3)),v[3],v[4]]);bow.tris.push(t);}};
 loopAt(-b.loop[0]/2-b.knot[0]*.3,-b.lift,b.loop[0],b.loop[1],b.thick,-1);loopAt(b.loop[0]/2+b.knot[0]*.3,b.lift,b.loop[0],b.loop[1],b.thick,1);
 loopAt(0,0,b.knot[0],b.knot[1],b.thick*1.2,0);
 // Tuft: a short collar that widens, then pointed ends.
 const f=step.tuft,top=[end[0],f.top,end[2]],mid=[end[0],f.top-(f.top-f.bottom)*.35,end[2]];
 {const from=tip.tris.length,a=ring(f.width[0]/2,f.depth/2).map(p=>add(p,top)),c=ring(f.width[1]/2,f.depth*.55).map(p=>add(p,mid)),u=sw.light[0]+6;
  tip.band(a,c,u,u,[sw.row[0]+1,sw.row[1]-1],binding.lower);tip.cap(a,top,u,sw.row[0]+8,binding.lower,true);tip.orient(from,lerp(top,mid,.5));}
 for(let i=0;i<f.points;i++){const k=f.points>1?i/(f.points-1)*2-1:0,base=add(mid,[k*f.width[1]*.32,0,0]);
  spike(tip,base,[k*f.spread,-1,0.04],(mid[1]-f.bottom)*(1-Math.abs(k)*.18),f.radius,f.radius*.9,sw,binding.lower,i*20);}
 return {lock:lock.tris,tip:tip.tris,ribbon:bow.tris};
}

/* Loose lock: a few pointed strands hanging from the root, ending at staggered heights (sprite 5802). */
function strands(step,texture,binding){
 const sw=step.swatches,mesh=new Mesh(texture);
 step.strands.forEach((s,i)=>{const top=add(step.root,s.offset),bottom=[top[0]+(s.dx||0),s.end,top[2]+(s.dz||0)];
  const dir=[bottom[0]-top[0],bottom[1]-top[1],bottom[2]-top[2]],L=Math.hypot(...dir),body=L*(s.body??0.62);
  const u=scale(dir,1/L),split=add(top,scale(u,body));
  // straight body as a tapered prism, then a sharp tip
  const a=ring(s.width/2,s.depth/2,6,0).map(p=>add(p,top)),c=ring(s.width*.42,s.depth*.42,6,0).map(p=>add(p,split)),from=mesh.tris.length;
  mesh.band(a,c,sw.mid[0]+4,sw.light[0]+8,[sw.row[0]+1,sw.row[1]-1],binding.upper);
  mesh.cap(a,top,sw.mid[0]+8,sw.row[0]+8,binding.upper,true);mesh.orient(from,lerp(top,split,.5));
  spike(mesh,split,dir,L-body,s.width*.42,s.depth*.42,sw,binding.upper,i*25);
 });
 return mesh.tris;
}
/* Brim frill (sprites 5680, 8105, the classic composite and 家ゆ p3): pleats of white ribbon hanging from under
   the front of the brim, just behind its edge, over the bangs. Pleats follow an arc of the ellipse (center [x,z],
   radii [rx,rz]) spanning `span` degrees around the front; long and short pleats alternate into a toothed hem
   and the drop eases off toward the arc ends, where the drooping brim takes over. */
function frill(step,texture,binding){
 const sw=step.swatches,mesh=new Mesh(texture),[cx,cz]=step.center,[rx,rz]=step.radii,n=step.count,span=step.span*Math.PI/180;
 const white=[sw.ribbon[0]+8,sw.ribbon[1]+8],shade=[sw.ribbon_shade[0]+8,sw.ribbon_shade[1]+8],t=step.thickness;
 for(let i=0;i<n;i++){const a=-Math.PI/2-span/2+span*i/n,b=-Math.PI/2-span/2+span*(i+1)/n,mid=(a+b)/2,e=(mid+Math.PI/2)/(span/2);
  // Pleats sit on the ellipse, or `radiusAt` (the head surface, see author.cjs) so the ruffle wraps the scalp;
  // `hug` pulls the arc ends in so they never stand off the head silhouette. `inset` is the pleat thickness.
  const drop=step.drop*(i%2?step.short:1)*(1-.45*e*e),hug=ang=>{const f=(ang+Math.PI/2)/(span/2);return 1-(step.hug||0)*f*f;},
   pt=(ang,inset)=>{if(step.radiusAt){const r=step.radiusAt(ang)*hug(ang)-inset;return [cx+Math.cos(ang)*r,step.y,cz+Math.sin(ang)*r];}
    const r=hug(ang)*(1-inset/rz);return [cx+Math.cos(ang)*rx*r,step.y,cz+Math.sin(ang)*rz*r];};
  const p0=pt(a,0),p1=pt(b,0),q0=pt(a,t),q1=pt(b,t),d=p=>[p[0],p[1]-drop,p[2]],from=mesh.tris.length;
  const face=i%2?shade:white;
  mesh.quad(p0,p1,d(p1),d(p0),face,face,face,face,binding);mesh.quad(q1,q0,d(q0),d(q1),shade,shade,shade,shade,binding);
  mesh.quad(d(p0),d(p1),d(q1),d(q0),shade,shade,shade,shade,binding);mesh.quad(p0,q0,d(q0),d(p0),shade,shade,shade,shade,binding);
  mesh.quad(q1,p1,d(p1),d(q1),shade,shade,shade,shade,binding);
  mesh.orient(from,[(p0[0]+p1[0]+q0[0]+q1[0])/4,step.y-drop/2,(p0[2]+p1[2]+q0[2]+q1[2])/4]);}
 return mesh.tris;
}
module.exports={braid,strands,frill};
