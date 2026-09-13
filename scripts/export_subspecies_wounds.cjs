/* Wound adapter for only the separately selected subspecies. Ordinary exports stay read-only. */
const fs=require('fs'),path=require('path'),crypto=require('crypto'),zlib=require('zlib');
const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const selected=path.join(root,'art/source/subspecies-runtime');
const source=require(path.join(root,'art/props/body-wounds/selected/wound-surface.js'));
const manifest=JSON.parse(fs.readFileSync(path.join(selected,'manifest.json'))),records={};
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
for(const id of Object.keys(manifest.models)){
 const file=path.join(assets,'yukkuri',id+'.json'),m=JSON.parse(fs.readFileSync(file));
 const points=[],weights=[],replaced=[],faceParts=[];let skinCount=0;
 const round=x=>Math.round(x*1e6)/1e6;
 const emit=vs=>{for(const v of vs){points.push(...v.p.map(round),...v.uv.map(round));const map=new Map();for(const [b,w]of v.w)map.set(b,(map.get(b)||0)+w);const kept=[...map].filter(([b,w])=>w>1e-5),sum=kept.reduce((s,[b,w])=>s+w,0);weights.push(kept.map(([b,w])=>[b,round(w/sum)]));}};
 const blend=(vs,c)=>{const w=new Map();vs.forEach((v,i)=>v.w.forEach(([b,q])=>w.set(b,(w.get(b)||0)+q*c[i])));return {p:[0,1,2].map(k=>vs.reduce((s,v,i)=>s+v.p[k]*c[i],0)),uv:[0,1].map(k=>vs.reduce((s,v,i)=>s+v.uv[k]*c[i],0)),w:[...w]};};
 let pre=null;
 if(!id.endsWith('_bean')){
  pre=source.prepare(m);replaced.push(...pre.boxes.map(b=>b.name));
  const cuts=[0,1,2].map(k=>{const step=k===1?.35:.4,lo=pre.center[k]-pre.half[k],hi=pre.center[k]+pre.half[k],values=[];
   for(let x=Math.ceil(lo/step);x*step<hi;x++)values.push(round(x*step));for(const f of pre.faces){const j=f.axes.indexOf(k);values.push(...(j<0?[f.plane]:[f.low[j],f.high[j]]));}return [...new Set(values.map(round))].sort((a,b)=>a-b);});
  for(const f of pre.faces){const xs=cuts[f.axes[0]].filter(x=>x>=f.low[0]-1e-6&&x<=f.high[0]+1e-6),ys=cuts[f.axes[1]].filter(y=>y>=f.low[1]-1e-6&&y<=f.high[1]+1e-6);
   const at=(x,y)=>{const u=(xs[x]-f.low[0])/(f.high[0]-f.low[0]),v=(ys[y]-f.low[1])/(f.high[1]-f.low[1]),p=blend(f.c,[(1-u)*(1-v),u*(1-v),(1-u)*v,u*v]);p.w=pre.influence(p.p);return p;};
   for(let x=0;x<xs.length-1;x++)for(let y=0;y<ys.length-1;y++){const a=at(x,y),b=at(x+1,y),c=at(x+1,y+1),d=at(x,y+1),ab=b.p.map((v,k)=>v-a.p[k]),ac=c.p.map((v,k)=>v-a.p[k]);const dot=(ab[1]*ac[2]-ab[2]*ac[1])*f.n[0]+(ab[2]*ac[0]-ab[0]*ac[2])*f.n[1]+(ab[0]*ac[1]-ab[1]*ac[0])*f.n[2];emit(dot>0?[a,b,c,a,c,d]:[a,c,b,a,d,c]);}
  }
 }
 const subdivide=p=>{const start=weights.length;replaced.push(p.name);
  for(let i=p.start;i<p.start+p.count;i+=3){
   if(p.name.startsWith('face_')&&m.vertices[i*8+7]>-.5)continue;
   const vs=[0,1,2].map(j=>{const n=i+j,v=m.vertices.slice(n*8,n*8+8),[a,b,t]=m.weights[n];return {p:v.slice(0,3),uv:v.slice(3,5),w:[[a,1-t],[b,t]]};});
   if(p.name==='deficient_body_surface'){
    // These source rings are already short vertically. Split their long edges instead
    // of squaring a horizontal subdivision count along both triangle coordinates.
    const split=(tri,depth=0)=>{let edge=0,longest=0;for(let e=0;e<3;e++){const q=tri[e].p.reduce((s,v,k)=>s+(v-tri[(e+1)%3].p[k])**2,0);if(q>longest){longest=q;edge=e;}}
     if(longest<=.55*.55||depth>=18){emit(tri);return;}
     const a=tri[edge],b=tri[(edge+1)%3],c=tri[(edge+2)%3],middle=blend([a,b],[.5,.5]);
     split([a,middle,c],depth+1);split([middle,b,c],depth+1);
    };split(vs);continue;
   }
   const steps=Math.max(1,Math.ceil(Math.max(...[1,2].map(j=>Math.hypot(...vs[j].p.map((v,k)=>v-vs[0].p[k]))))/.35));
   const at=(a,b)=>blend(vs,[1-a/steps-b/steps,a/steps,b/steps]);
   for(let a=0;a<steps;a++)for(let b=0;b<steps-a;b++){emit([at(a,b),at(a+1,b),at(a,b+1)]);if(a+b<steps-1)emit([at(a+1,b),at(a+1,b+1),at(a,b+1)]);}
  }
  if(p.name.startsWith('face_')){const bone=m.weights[p.start][0];faceParts.push({name:p.name,start,count:weights.length-start,bone,parent:m.bones[bone].parent});}
 };
 if(!pre)for(const p of m.parts)if(p.name==='deficient_body_surface')subdivide(p);
 skinCount=weights.length;
 for(const p of m.parts)if(p.name.startsWith('face_')&&!p.anatomy)subdivide(p);
 const presentation=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/subspecies.json')))[id];
 const envelope=m.parts.filter(p=>!p.anatomy&&!p.name.includes('puff')).map(p=>({name:p.name,x:m.vertices[p.start*8],yz:[...new Set(Array.from({length:p.count},(_,j)=>[m.vertices[(p.start+j)*8+1],m.vertices[(p.start+j)*8+2]].join(',')))].map(s=>s.split(',').map(Number))}));
 const data={version:1,id,runtimeSourceHash:hash(file),points,weights,skinCount,replaced,faceParts,bones:m.bones,clips:m.clips,envelope,accessoryPrefixes:presentation.accessory_prefixes};
 const out=path.join(assets,'yukkuri_wounds',id+'.json.gz');fs.writeFileSync(out,zlib.gzipSync(JSON.stringify(data),{level:9}));
 records[id]={runtime_sha256:hash(file),surface_sha256:hash(out),skin_vertices:skinCount,face_vertices:weights.length-skinCount};
 console.log(id,skinCount,weights.length);
}
fs.writeFileSync(path.join(selected,'wounds-manifest.json'),JSON.stringify({exporter:'scripts/export_subspecies_wounds.cjs',sha256:hash(__filename),models:records},null,2));
