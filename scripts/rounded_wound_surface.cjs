/* Continuous rounded skin adapter. Preserves interpolated source weights and expression geometry. */
function roundedSurface(model,hash,presentation){
 const points=[],weights=[],replaced=[],faceParts=[],expressionPoints={};let skinCount=0;
 const round=x=>Math.round(x*1e6)/1e6;
 const patches={};for(const [expr,entries]of Object.entries(model.expression_patches||{}))patches[expr]=new Map(entries.map(e=>[e.index,e.vertices]));
 const blend=(a,b)=>{
  const mix=key=>a[key].map((v,k)=>(v+b[key][k])/2),w=new Map(),sources=new Map();
  for(const v of [a,b]){for(const [bone,q]of v.w)w.set(bone,(w.get(bone)||0)+q*.5);for(const [i,q]of v.source)sources.set(i,(sources.get(i)||0)+q*.5);}
  return {p:mix('p'),uv:mix('uv'),w:[...w],source:[...sources]};
 };
 const subdivide=(part,index)=>{
  const start=weights.length,changed={};for(const [expr,map]of Object.entries(patches))if(map.has(index))changed[expr]=[];
  replaced.push(part.name);
  const emit=tri=>{for(const v of tri){points.push(...v.p.map(round),...v.uv.map(round));
   const merged=new Map();for(const [bone,q]of v.w)merged.set(bone,(merged.get(bone)||0)+q);
   const kept=[...merged].filter(([b,q])=>q>1e-6),total=kept.reduce((s,[b,q])=>s+q,0);weights.push(kept.map(([b,q])=>[b,round(q/total)]));
   for(const [expr,out]of Object.entries(changed)){const vv=patches[expr].get(index);out.push(...[0,1,2,3,4].map(k=>round(v.source.reduce((sum,[i,q])=>sum+vv[i*8+k]*q,0))));}
  }};
  const split=(tri,depth=0)=>{
   let edge=0,longest=0;for(let i=0;i<3;i++){const d=tri[i].p.reduce((s,x,k)=>s+(x-tri[(i+1)%3].p[k])**2,0);if(d>longest){edge=i;longest=d;}}
   if(longest<=.45*.45||depth>=18){emit(tri);return;}
   const a=tri[edge],b=tri[(edge+1)%3],c=tri[(edge+2)%3],mid=blend(a,b);split([a,mid,c],depth+1);split([mid,b,c],depth+1);
  };
  for(let i=part.start;i<part.start+part.count;i+=3){
   const tri=[0,1,2].map(k=>{const vertex=i+k,v=model.vertices.slice(vertex*8,vertex*8+8),[a,b,t]=model.weights[vertex];return {p:v.slice(0,3),uv:v.slice(3,5),w:[[a,1-t],[b,t]],source:[[vertex-part.start,1]]};});split(tri);
  }
  for(const [expr,out]of Object.entries(changed))(expressionPoints[expr]??=[]).push({start,points:out});
  if(part.name.startsWith('face_')){const bone=part.feature_bone??model.weights[part.start][0];faceParts.push({name:part.name,start,count:weights.length-start,bone,parent:model.bones[bone].parent});}
 };
 model.parts.forEach((p,i)=>{if(p.name==='rounded_body_surface')subdivide(p,i);});skinCount=weights.length;
 model.parts.forEach((p,i)=>{if(p.name==='rounded_rump_mark'||p.name.startsWith('face_')&&!p.anatomy)subdivide(p,i);});
 const envelope=model.parts.filter(p=>!p.anatomy&&!p.name.includes('puff')).map(p=>({name:p.name,x:model.vertices[p.start*8],yz:[...new Set(Array.from({length:p.count},(_,j)=>[model.vertices[(p.start+j)*8+1],model.vertices[(p.start+j)*8+2]].join(',')))].map(s=>s.split(',').map(Number))}));
 return {version:2,id:model.id,runtimeSourceHash:hash,points,weights,skinCount,replaced,faceParts,bones:model.bones,clips:model.clips,envelope,accessoryPrefixes:presentation.accessory_prefixes,expressionPoints};
}
module.exports={roundedSurface};
if(require.main===module){
 const fs=require('fs'),path=require('path'),crypto=require('crypto'),zlib=require('zlib');
 const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri'),out=path.join(root,'art/source/rounded-runtime');
 const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
 const manifest=JSON.parse(fs.readFileSync(path.join(out,'manifest.json'))),presentations=JSON.parse(fs.readFileSync(path.join(out,'presentation.json')));
 const records={};for(const id of Object.keys(manifest.models)){
  const p=path.join(assets,'yukkuri',id+'.json'),m=JSON.parse(fs.readFileSync(p));
  const data=roundedSurface(m,sha(p),presentations.living.variants[id]||presentations.subspecies[id]);
  const target=path.join(assets,'yukkuri_wounds',id+'.json.gz');fs.writeFileSync(target,zlib.gzipSync(JSON.stringify(data),{level:9}));
  records[id]={runtime_sha256:sha(p),surface_sha256:sha(target),skin_vertices:data.skinCount,total_vertices:data.weights.length};console.log(id,data.skinCount,data.weights.length);
 }
 fs.writeFileSync(path.join(out,'wounds-manifest.json'),JSON.stringify({exporter:'scripts/rounded_wound_surface.cjs',exporter_sha256:sha(__filename),models:records},null,2)+'\n');
}
