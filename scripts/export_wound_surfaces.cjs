/* Runtime adapter: selected R3.1 union skin and shared binding kernel. */
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'..'),base=path.join(root,'art/props/body-wounds');
const source=require(path.join(base,'selected/wound-surface.js'));
const out=path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_wounds');
fs.mkdirSync(out,{recursive:true});
const sha=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const manifest={version:1,source:'art/props/body-wounds/selected/wound-surface.js',source_sha256:sha(path.join(base,'selected/wound-surface.js')),models:{}};
const selected=process.argv.find(a=>a.startsWith('--ids='))?.slice(6).split(',');
if(selected&&fs.existsSync(path.join(base,'provenance.json')))Object.assign(manifest.models,JSON.parse(fs.readFileSync(path.join(base,'provenance.json'))).models);
for(const id of selected||['reimu','marisa','chen','alice','patchouli','house_reimu','house_marisa','remilia','flandre']){
 const file=path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri',id+'.json'),m=JSON.parse(fs.readFileSync(file));
 const points=[],weights=[],parts=[],replaced=[];let skinCount=0;
 const round=x=>Math.round(x*1e6)/1e6;
 const emit=vs=>{for(const v of vs){points.push(...v.p.map(round),...v.uv.map(round));const kept=v.w.filter(([b,w])=>w>1e-5),sum=kept.reduce((s,[b,w])=>s+w,0);weights.push(kept.map(([b,w])=>[b,round(w/sum)]));}};
 const blend=(vs,coeff)=>{const w=new Map();vs.forEach((v,i)=>v.w.forEach(([b,q])=>w.set(b,(w.get(b)||0)+q*coeff[i])));return {p:[0,1,2].map(k=>vs.reduce((s,v,i)=>s+v.p[k]*coeff[i],0)),uv:[0,1].map(k=>vs.reduce((s,v,i)=>s+v.uv[k]*coeff[i],0)),w:[...w]};};
 let pre=null;
 if(!id.startsWith('house_')&&!['remilia','flandre'].includes(id)){
  pre=source.prepare(m);replaced.push(...pre.boxes.map(b=>b.name));
  // Every shared edge receives the same cuts, including T junctions between source slices.
  const cuts=[0,1,2].map(k=>{const step=k===1?.35:.4,low=pre.center[k]-pre.half[k],high=pre.center[k]+pre.half[k],values=[];
   for(let x=Math.ceil(low/step);x*step<high;x++)values.push(round(x*step));for(const f of pre.faces){const i=f.axes.indexOf(k);values.push(...(i<0?[f.plane]:[f.low[i],f.high[i]]));}return [...new Set(values.map(round))].sort((a,b)=>a-b);});
  for(const f of pre.faces){const xs=cuts[f.axes[0]].filter(x=>x>=f.low[0]-1e-6&&x<=f.high[0]+1e-6),ys=cuts[f.axes[1]].filter(y=>y>=f.low[1]-1e-6&&y<=f.high[1]+1e-6),nx=xs.length-1,ny=ys.length-1;
   const at=(x,y)=>{const u=(xs[x]-f.low[0])/(f.high[0]-f.low[0]),v=(ys[y]-f.low[1])/(f.high[1]-f.low[1]),p=blend(f.c,[(1-u)*(1-v),u*(1-v),(1-u)*v,u*v]);p.p=p.p.map(round);p.w=pre.influence(p.p);return p;};
   for(let x=0;x<nx;x++)for(let y=0;y<ny;y++){const a=at(x,y),b=at(x+1,y),c=at(x+1,y+1),d=at(x,y+1);const ab=b.p.map((v,k)=>v-a.p[k]),ac=c.p.map((v,k)=>v-a.p[k]);const dot=(ab[1]*ac[2]-ab[2]*ac[1])*f.n[0]+(ab[2]*ac[0]-ab[0]*ac[2])*f.n[1]+(ab[0]*ac[1]-ab[1]*ac[0])*f.n[2];emit(dot>0?[a,b,c,a,c,d]:[a,c,b,a,d,c]);}
  }
 }
 const subdivide=part=>{const start=weights.length;replaced.push(part.name);
  const emitFace=emit;
  for(let i=part.start;i<part.start+part.count;i+=3){const vs=[0,1,2].map(j=>{const n=i+j,v=m.vertices.slice(n*8,n*8+8),[a,b,t]=m.weights[n];return {p:v.slice(0,3),uv:v.slice(3,5),w:[[a,1-t],[b,t]]};});
  if(pre&&part.name.startsWith('face_')){
   // Clip the visible glyph wafer to the exact skin triangulation. No buried back or cross-cell interpolation.
   if(m.vertices[i*8+7]>-.5)continue;
   const side=(a,b,p)=>(b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0]);
   const bary=(tri,p)=>{const det=side(tri[0],tri[1],tri[2]),b=side(tri[0],p,tri[2])/det,c=side(tri[0],tri[1],p)/det;return [1-b-c,b,c];};
   const xy=vs.map(v=>v.p.slice(0,2)),min=[0,1].map(k=>Math.min(...xy.map(p=>p[k]))),max=[0,1].map(k=>Math.max(...xy.map(p=>p[k])));
   for(let j=0;j<skinCount;j+=3){const skin=[0,1,2].map(k=>({p:points.slice((j+k)*5,(j+k)*5+3),w:weights[j+k]})),tri=skin.map(v=>v.p.slice(0,2));
    const det=side(tri[0],tri[1],tri[2]);if(det>=-1e-8||Math.abs(skin[0].p[2]-vs[0].p[2])>.2||[0,1].some(k=>Math.max(...tri.map(p=>p[k]))<min[k]||Math.min(...tri.map(p=>p[k]))>max[k]))continue;
    let polygon=xy;
    for(let k=0;k<3&&polygon.length;k++){const a=tri[k],b=tri[(k+1)%3],next=[];let prev=polygon.at(-1),pd=-side(a,b,prev);
     for(const cur of polygon){const cd=-side(a,b,cur);if((pd>=0)!=(cd>=0)){const t=pd/(pd-cd);next.push(prev.map((v,n)=>v+(cur[n]-v)*t));}if(cd>=0)next.push(cur);prev=cur;pd=cd;}polygon=next;}
    const at=p=>{const v=blend(vs,bary(xy,p)),bc=bary(tri,p),wm=new Map();skin.forEach((s,k)=>s.w.forEach(([b,w])=>wm.set(b,(wm.get(b)||0)+w*bc[k])));v.w=[...wm];return v;};
    for(let k=1;k+1<polygon.length;k++)emit([at(polygon[0]),at(polygon[k]),at(polygon[k+1])]);
   }continue;
  }
  const steps=Math.max(1,Math.ceil(Math.max(...[1,2].map(j=>Math.hypot(...vs[j].p.map((v,k)=>v-vs[0].p[k]))))/.35));
  const at=(x,y)=>{const v=blend(vs,[1-x/steps-y/steps,x/steps,y/steps]);if(pre)v.w=pre.influence(v.p);return v;};
  for(let a=0;a<steps;a++)for(let b=0;b<steps-a;b++){emitFace([at(a,b),at(a+1,b),at(a,b+1)]);if(a+b<steps-1)emitFace([at(a+1,b),at(a+1,b+1),at(a,b+1)]);}
 }if(pre&&part.name.startsWith('face_')){const bone=m.weights[part.start][0];parts.push({name:part.name,start,count:weights.length-start,bone,parent:m.bones[bone].parent});}};
 if(!pre)for(const p of m.parts)if(p.name==='house_body_surface'||p.name==='predator_body_surface')subdivide(p);
 skinCount=weights.length;
 for(const p of m.parts)if(p.name.startsWith('face_')&&!p.anatomy)subdivide(p);
 const presentation=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/living.json'))).variants[id];
 const envelope=m.parts.filter(p=>!p.anatomy&&!p.name.includes('puff')).map(p=>({name:p.name,x:m.vertices[p.start*8],yz:[...new Set(Array.from({length:p.count},(_,j)=>[m.vertices[(p.start+j)*8+1],m.vertices[(p.start+j)*8+2]].join(',')))].map(s=>s.split(',').map(Number))}));
 const clips=['remilia','flandre'].includes(id)?{...m.clips,...m.behavior_clips}:m.clips;
 const result={version:1,id,runtimeSourceHash:sha(file),points,weights,skinCount,replaced,faceParts:parts,bones:m.bones,clips,envelope,accessoryPrefixes:presentation.accessory_prefixes};
 const target=path.join(out,id+'.json.gz');fs.writeFileSync(target,require('zlib').gzipSync(JSON.stringify(result),{level:9}));
 const obsolete=path.join(out,id+'.json');if(fs.existsSync(obsolete))fs.unlinkSync(obsolete);
 manifest.models[id]={geometry_identifier:id,runtime_source:path.relative(root,file).replaceAll('\\','/'),source_sha256:sha(file),bones:m.bones.length,animations:Object.keys(m.clips),texture:`textures/entity/${id}/<expression>.png`,output:path.relative(root,target).replaceAll('\\','/'),output_sha256:sha(target),skin_vertices:skinCount,total_vertices:weights.length};
 console.log(id,skinCount,weights.length);
}
const palette=path.join(base,'selected/palette.png'),paletteOut=path.join(root,'src/main/resources/assets/mcyukkuri/textures/props/body_wounds.png');fs.copyFileSync(palette,paletteOut);
manifest.palette={source:'art/props/body-wounds/selected/palette.png',source_sha256:sha(palette),output:'src/main/resources/assets/mcyukkuri/textures/props/body_wounds.png',output_sha256:sha(paletteOut)};
manifest.exporter={path:'scripts/export_wound_surfaces.cjs',sha256:sha(__filename)};
fs.writeFileSync(path.join(base,'provenance.json'),JSON.stringify(manifest,null,2)+'\n');
const ledgerPath=path.join(root,'art/runtime-sources.json'),ledger=JSON.parse(fs.readFileSync(ledgerPath));
const approvalPath='standards/yukkuri-library/ecology/body-wounds/approval.json';
let approved=false;
if(fs.existsSync(path.join(root,approvalPath))){const approval=JSON.parse(fs.readFileSync(path.join(root,approvalPath)));
 const geometryHash=crypto.createHash('sha256').update(fs.readFileSync(__filename,'utf8').split('const ledgerPath=')[0]).digest('hex');
 approved=approval.status==='user_approved'&&approval.exporter_geometry_sha256===geometryHash&&Object.entries(approval.current_asset_sha256).every(([file,hash])=>fs.existsSync(path.join(root,file))&&sha(path.join(root,file))===hash);
}
ledger.body_wounds={selected_source:manifest.source,provenance:'art/props/body-wounds/provenance.json',exporter:'scripts/export_wound_surfaces.cjs',runtime_directory:'src/main/resources/assets/mcyukkuri/yukkuri_wounds',preview:'art/props/body-wounds/previews/current/index.html',standard_status:approved?'user_approved':'pending_user_confirmation',approval:approvalPath};
fs.writeFileSync(ledgerPath,JSON.stringify(ledger,null,2)+'\n');
