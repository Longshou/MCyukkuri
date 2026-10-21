/* Offline adapter for the selected cavity resources. Mirrors BodyCavity's rest
 * fit and loft; surface.cjs clips pixels, while Java caches clipped triangles.
 * This view does not validate animated skinning or the Minecraft renderer. */
'use strict';
const fs=require('fs'),path=require('path'),zlib=require('zlib');
const root=path.resolve(__dirname,'../../../..');
const assets=path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation');
const add=(a,b)=>a.map((v,k)=>v+b[k]),sub=(a,b)=>a.map((v,k)=>v-b[k]),mul=(a,s)=>a.map(v=>v*s);
const dot=(a,b)=>a.reduce((s,v,k)=>s+v*b[k],0);
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const unit=a=>mul(a,1/Math.hypot(...a));
function createRuntimeCavity(vertices,skin,state='intact'){
  const spec=JSON.parse(fs.readFileSync(path.join(assets,'body_cavity.json')));
  const core=JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(assets,'body_core.json.gz'))));
  const points=[];for(let i=skin.start*8;i<(skin.start+skin.count)*8;i+=8)points.push(Array.from(vertices.slice(i,i+3)));
  const low=[0,1,2].map(k=>Math.min(...points.map(p=>p[k]))),high=[0,1,2].map(k=>Math.max(...points.map(p=>p[k]))),span=sub(high,low);
  const center=add(low,spec.center.map((v,k)=>v*span[k]));
  function ray(origin,direction){
    let found=null;
    for(let i=0;i<points.length;i+=3){
      const a=points[i],e=sub(points[i+1],a),f=sub(points[i+2],a),h=cross(direction,f),det=dot(e,h);
      if(det<1e-7)continue;
      const s=sub(origin,a),u=dot(s,h)/det,q=cross(s,e),v=dot(direction,q)/det,t=dot(f,q)/det;
      if(u<-.0001||u>1.0001||v<-.0001||u+v>1.0001||t<0||found&&found.t<t)continue;
      found={point:add(origin,mul(direction,t)),normal:unit(cross(e,f)),t};
    }
    return found;
  }
  const hit=ray([2.1,4.2,low[2]-30],[0,0,1]);if(!hit)throw Error('Missing preview injury site');
  const anchor=hit.point,n=hit.normal,u=Math.abs(n[1])>.9?[1,0,0]:unit(cross([0,1,0],n)),v=cross(n,u);
  let ru=dot(u.map(Math.abs),span)*spec.opening_radius[0],rv=dot(v.map(Math.abs),span)*spec.opening_radius[1];
  const depth=dot(sub(anchor,center),n),depthScale=depth/spec.reference_depth,range=Math.hypot(...span)*2;
  let rim=null;
  for(let attempt=0;attempt<12;attempt++){
    const candidate=spec.outline.map(p=>ray(add(add(add(anchor,mul(u,p[0]*ru)),mul(v,p[1]*rv)),mul(n,range)),mul(n,-1)));
    if(candidate.every(h=>h&&dot(sub(h.point,center),n)>depth*.06)){rim=candidate.map(h=>h.point);break;}
    ru*=.86;rv*=.86;
  }
  if(!rim)throw Error('Cannot fit selected preview opening');
  const polygon=spec.outline.map(p=>[p[0]*ru,p[1]*rv]);
  function inside(x,y,z){
    const p=[x,y,z];if(dot(sub(p,center),n)<=.25*depthScale)return false;
    const d=sub(p,anchor),px=dot(d,u),py=dot(d,v);let yes=false;
    for(let i=0,j=polygon.length-1;i<polygon.length;j=i++){
      const a=polygon[i],b=polygon[j];if((a[1]>py)!=(b[1]>py)&&px<(b[0]-a[0])*(py-a[1])/(b[1]-a[1])+a[0])yes=!yes;
    }
    return yes;
  }
  const out=[],colors=[],materials=[],lateral=sub(sub(center,anchor),mul(n,dot(sub(center,anchor),n)));
  function triangle(a,b,c,color,kind,normals=null){
    const norm=cross(sub(b,a),sub(c,a));if(Math.hypot(...norm)<1e-8)return;
    const material=colors.length;colors.push(color);materials.push(kind);
    [a,b,c].forEach((p,j)=>out.push(...p,-material-1,0,...(normals?normals[j]:unit(norm))));
  }
  const rings=spec.levels.map(level=>rim.map(p=>{
    const delta=sub(p,anchor);
    return add(add(add(add(anchor,mul(lateral,level[2])),mul(u,dot(delta,u)*level[0])),mul(v,dot(delta,v)*level[0])),mul(n,dot(delta,n)-level[1]*depthScale));
  }));
  for(let r=0;r<rings.length-1;r++)for(let j=0;j<rim.length;j++){
    const k=(j+1)%rim.length,a=rings[r][j],b=rings[r][k],c=rings[r+1][k],d=rings[r+1][j];
    triangle(a,b,c,spec.levels[r][3],r<2?'dough':'filling');triangle(a,c,d,spec.levels[r][3],r<2?'dough':'filling');
  }
  if(state!=='empty')for(let i=0;i<core.vertices.length;i+=18){
    const positions=[],normals=[];
    for(let j=0;j<3;j++){
      positions.push(add(center,core.vertices.slice(i+j*6,i+j*6+3).map((x,k)=>x*span[k])));
      normals.push(unit(core.vertices.slice(i+j*6+3,i+j*6+6).map((x,k)=>x/span[k])));
    }
    const colours=state==='broken'?core.broken_colours:core.colours;
    triangle(...positions,colours.slice(i/6,i/6+3),'core',normals);
  }
  // The runtime smooths shared loft vertices; preserve exported core normals.
  const groups=new Map(),keys=[];
  for(let i=0;i<out.length;i+=8){const kind=materials[-1-out[i+3]];
    const key=kind==='filling'?out.slice(i,i+3).map(v=>Math.round(v*1e5)).join(','):String(i);
    keys.push(key);if(!groups.has(key))groups.set(key,[0,0,0]);const sum=groups.get(key);for(let k=0;k<3;k++)sum[k]+=out[i+5+k];}
  for(let i=0;i<out.length;i+=8){const norm=unit(groups.get(keys[i/8]));for(let k=0;k<3;k++)out[i+5+k]=norm[k];}
  return {vertices:out,colors,materials,inside,coreCenter:center};
}
module.exports={createRuntimeCavity};
