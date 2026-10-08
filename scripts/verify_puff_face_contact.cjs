/* Exact puff contact regression on the runtime resource; offline, never drives a UI.

   For every model and sampled puff time (inflate, hold, release) it checks with
   triangle-triangle tests that the grown skin enters no hair, accessory or other
   solid that it did not already touch at rest, that no corner of a part already
   rooted in the skin becomes newly buried, that swung side locks enter no other
   solid, that the skin never crosses itself, that opaque eye, brow, mouth and
   blush texels keep no skin between them and the viewer side of their own face,
   that the blush stays within its gap above the skin, and that nothing sinks
   below the floor. */
const fs=require('fs'),path=require('path'),cp=require('child_process'),os=require('os'),crypto=require('crypto');
const {RuntimePose,puffAmount,spliceFit}=require('../preview/review/pose.js');
// The game splices the placement fit before the motion revision; check the same meshes.
const fits=JSON.parse(fs.readFileSync(path.join(__dirname,'../src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'))).models;
const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const runtime=path.join(assets,'yukkuri_presentation/motion_corrections.json');
const temporary=path.join(root,process.env.PUFF_WORK||'.local/puff-v7/contact');fs.mkdirSync(temporary,{recursive:true});
const python=process.env.REVIEW_PYTHON||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe');
const revisions=JSON.parse(fs.readFileSync(runtime)).models;
const ids=(process.env.PUFF_IDS||Object.keys(revisions).join(',')).split(',');
const times=(process.env.PUFF_TIMES||'0.2,0.35,0.7,1.0,1.4,3.2,5.6,6.2').split(',').map(Number);

function sub(a,b){return [a[0]-b[0],a[1]-b[1],a[2]-b[2]];}
function cross(a,b){return [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];}
function dot(a,b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
/* Segment p->q strictly crosses triangle (a,b,c); touching within tol does not count. */
function segmentHits(p,q,a,b,c,tol){
 const d=sub(q,p),e1=sub(b,a),e2=sub(c,a),h=cross(d,e2),det=dot(e1,h);if(Math.abs(det)<1e-12)return false;
 const f=1/det,s=sub(p,a),u=f*dot(s,h);if(u<tol||u>1-tol)return false;
 const qv=cross(s,e1),v=f*dot(d,qv);if(v<tol||u+v>1-tol)return false;
 const t=f*dot(e2,qv);return t>tol&&t<1-tol;
}
function intersect(A,B,tol=1e-7){for(let i=0;i<3;i++){if(segmentHits(A[i],A[(i+1)%3],B[0],B[1],B[2],tol)||segmentHits(B[i],B[(i+1)%3],A[0],A[1],A[2],tol))return true;}return false;}
function triangles(data,part){const out=[];for(let k=part.start*8;k<(part.start+part.count)*8;k+=24){
 const T=[[data[k],data[k+1],data[k+2]],[data[k+8],data[k+9],data[k+10]],[data[k+16],data[k+17],data[k+18]]];
 if(Math.hypot(...cross(sub(T[1],T[0]),sub(T[2],T[0])))>1e-10)out.push(T);}return out;}
class Grid{constructor(cell){this.cell=cell;this.map=new Map();}
 keys(T){const c=this.cell,lo=[0,1,2].map(a=>Math.floor(Math.min(T[0][a],T[1][a],T[2][a])/c)),hi=[0,1,2].map(a=>Math.floor(Math.max(T[0][a],T[1][a],T[2][a])/c)),out=[];
  for(let x=lo[0];x<=hi[0];x++)for(let y=lo[1];y<=hi[1];y++)for(let z=lo[2];z<=hi[2];z++)out.push(x+','+y+','+z);return out;}
 add(T,id){for(const k of this.keys(T)){let l=this.map.get(k);if(!l)this.map.set(k,l=[]);l.push(id);}}
 near(T){const s=new Set();for(const k of this.keys(T))for(const id of this.map.get(k)||[])s.add(id);return s;}}
function inside(point,tris){const far=[point[0]+1e4,point[1]+1,point[2]+2.3];let n=0;
 for(const T of tris){if(Math.max(T[0][0],T[1][0],T[2][0])<point[0])continue;
  if(Math.min(T[0][1],T[1][1],T[2][1])>point[1]+.05||Math.max(T[0][1],T[1][1],T[2][1])<point[1]-.05)continue;
  if(Math.min(T[0][2],T[1][2],T[2][2])>point[2]+.08||Math.max(T[0][2],T[1][2],T[2][2])<point[2]-.08)continue;
  if(segmentHits(point,far,T[0],T[1],T[2],0))n++;}return n%2===1;}
function crossings(p,dir,len,skin,grid){const q=[p[0]+dir[0]*len,p[1]+dir[1]*len,p[2]+dir[2]*len];let n=0;
 for(const s of grid.near([p,q,q]))if(segmentHits(p,q,skin[s][0],skin[s][1],skin[s][2],1e-9))n++;return n;}

cp.execFileSync(python,['-c',String.raw`
import sys
from pathlib import Path
from PIL import Image
r=Path(sys.argv[1]);o=Path(sys.argv[2])
for d in (r/'textures/entity').iterdir():
 p=d/'cheek_puff.png'
 if p.exists():(o/(d.name+'-cheek_puff.texture')).write_bytes(Image.open(p).convert('RGBA').tobytes())
`,assets,temporary]);
const report={createdAt:new Date().toISOString(),scope:'Offline exact contact at sampled puff times: grown skin vs solids (new intersections and newly buried rooted corners), swung locks vs solids, skin self-intersection, opaque facial ink and blush occlusion, blush gap and floor. Pre-existing rest contacts are the baseline. No live game, lighting, wounds, pregnancy or carrying.',times,models:[]};
const failures=[];
for(const id of ids){
 const model=spliceFit(JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',id+'.json'))),fits[id]||null),rev=revisions[id],size=model.texture_size||128;
 const texture=fs.readFileSync(path.join(temporary,id+'-cheek_puff.texture'));
 const pose=new RuntimePose(model,1,rev);pose.expression('cheek_puff');
 const bodyIndex=rev.puff_surface.part_index,body=model.parts[bodyIndex],moved=new Set((rev.puff_offsets||[]).map(o=>o.part_index));
 const isFace=i=>model.parts[i].name.startsWith('face_');
 const solids=puffing=>model.parts.map((p,i)=>({p:puffing&&pose.puffAttachments.get(i)||p,i})).filter(({p,i})=>i!==bodyIndex&&!p.anatomy&&!isFace(i));
 const rest=Float64Array.from(pose.frame('puff',0)),restSkin=triangles(rest,body),g0=new Grid(1);restSkin.forEach((T,j)=>g0.add(T,j));
 const restSolids=new Map(solids(false).map(({p,i})=>[i,triangles(rest,p)]));
 const touching=new Set();for(const [i,tris] of restSolids)tris.forEach((T,j)=>{for(const s of g0.near(T))if(intersect(T,restSkin[s])){touching.add(i+':'+j);break;}});
 const still=[],gs=new Grid(1);for(const [i,tris] of restSolids)if(!moved.has(i))tris.forEach((T,j)=>{gs.add(T,still.length);still.push({i,j,T});});
 const lockBase=new Set();for(const i of moved)restSolids.get(i).forEach((T,j)=>{for(const s of gs.near(T))if(intersect(T,still[s].T))lockBase.add(i+':'+j+'/'+still[s].i+':'+still[s].j);});
 const row={id,skinHits:0,buried:0,lockHits:0,selfHits:0,inkSamples:0,buriedInk:0,blushSamples:0,maxBlushGap:0,floor:Infinity,amounts:[]};
 const restKeys=[];const s0=pose.puffSurface;for(let k=s0.start*8;k<(s0.start+s0.count)*8;k+=24)restKeys.push([0,1,2].map(j=>[0,1,2].map(a=>Math.round(pose.model.vertices[k+j*8+a]*1e4)).join(',')));
 for(const t of times){
  const data=Float64Array.from(pose.frame('puff',t));row.amounts.push(+puffAmount(pose.clips.puff,t).toFixed(4));
  const skin=triangles(data,pose.puffSurface),g=new Grid(1);skin.forEach((T,j)=>g.add(T,j));
  for(const {p,i} of solids(true))triangles(data,p).forEach((T,j)=>{
   let hit=false;for(const s of g.near(T))if(intersect(T,skin[s])){hit=true;break;}
   if(hit&&!touching.has(i+':'+j)){row.skinHits++;if(failures.length<20)failures.push({id,t,part:model.parts[i].name,kind:'skin enters solid'});}
   if(touching.has(i+':'+j)){const R=restSolids.get(i)[j];for(let c=0;c<3;c++)if(!inside(R[c],restSkin)&&inside(T[c],skin)){row.buried++;if(failures.length<20)failures.push({id,t,part:model.parts[i].name,kind:'rooted corner buried'});break;}}
  });
  const now=[],gn=new Grid(1);for(const {p,i} of solids(true))if(!moved.has(i))triangles(data,p).forEach((T,j)=>{gn.add(T,now.length);now.push({i,j,T});});
  for(const i of moved)triangles(data,model.parts[i]).forEach((T,j)=>{for(const s of gn.near(T)){const q=now[s];
   if(!lockBase.has(i+':'+j+'/'+q.i+':'+q.j)&&intersect(T,q.T)){row.lockHits++;if(failures.length<20)failures.push({id,t,part:model.parts[i].name,with:model.parts[q.i].name,kind:'lock enters solid'});}}});
  const gself=new Grid(.5);skin.forEach((T,j)=>gself.add(T,j));
  skin.forEach((A,i)=>{for(const j of gself.near(A)){if(j<=i)continue;if(restKeys[i].some(k=>restKeys[j].includes(k)))continue;if(intersect(A,skin[j])){row.selfHits++;if(failures.length<20)failures.push({id,t,kind:'skin self-intersection'});}}});
  model.parts.forEach((original,i)=>{if(!/^face_(eye_|brow_|mouth$|blush_)/.test(original.name))return;
   const part=pose.puffAttachments.get(i)||original,blush=original.name.startsWith('face_blush_');
   for(let k=part.start*8;k<(part.start+part.count)*8;k+=24){
    if(!pose.puffAttachments.has(i)&&!(pose.src[k+7]<-.2))continue;
    const n=[data[k+5],data[k+6],data[k+7]];if(Math.hypot(...n)<.5)continue;
    for(let a=0;a<6;a++)for(let b=0;b<6-a;b++){const wa=(a+1/3)/6,wb=(b+1/3)/6,wc=1-wa-wb;
     const u=wa*data[k+3]+wb*data[k+11]+wc*data[k+19],v=wa*data[k+4]+wb*data[k+12]+wc*data[k+20];
     const px=(Math.min(size-1,Math.max(0,Math.floor(v*size)))*size+Math.min(size-1,Math.max(0,Math.floor(u*size))))*4;if(texture[px+3]<128)continue;
     const P=[0,1,2].map(c=>wa*data[k+c]+wb*data[k+8+c]+wc*data[k+16+c]);row.inkSamples++;
     if(crossings(P,n,30,skin,g)>0){row.buriedInk++;if(failures.length<20)failures.push({id,t,part:original.name,kind:'ink covered by skin',at:P.map(x=>+x.toFixed(3))});}
     if(blush){row.blushSamples++;let gap=null;for(const L of [.005,.01,.02,.04,.06,.1,.2])if(crossings(P,[-n[0],-n[1],-n[2]],L,skin,g)>0){gap=L;break;}
      row.maxBlushGap=Math.max(row.maxBlushGap,gap===null?Infinity:gap);}
    }}});
  for(let k=pose.puffSurface.start*8+1;k<(pose.puffSurface.start+pose.puffSurface.count)*8;k+=8)row.floor=Math.min(row.floor,data[k]);
 }
 const floor=Math.min(...model.parts.filter(p=>p.name.includes('body_surface')).flatMap(p=>Array.from({length:p.count},(_,v)=>model.vertices[(p.start+v)*8+1])));
 if(row.floor<floor-1e-6){failures.push({id,kind:'below floor',value:row.floor});}
 const blushLimit=rev.cheek.face_scale*.03;
 if(row.maxBlushGap>blushLimit)failures.push({id,kind:'blush floats',value:row.maxBlushGap,limit:blushLimit});
 row.blushGapLimit=blushLimit;row.baseFloor=floor;report.models.push(row);
 console.log(id,'skin',row.skinHits,'buried',row.buried,'locks',row.lockHits,'self',row.selfHits,'ink',row.buriedInk+'/'+row.inkSamples,'blush gap<=',row.maxBlushGap);
}
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
Object.assign(report,{runtimeSha256:hash(runtime),placementFitSha256:hash(path.join(assets,'yukkuri_presentation/placement_fit.json')),poseSha256:hash(path.join(root,'preview/review/pose.js')),scriptSha256:hash(__filename),failures});
const dest=path.join(root,'art/source/motion-corrections/reports/current');fs.mkdirSync(dest,{recursive:true});
// FACE_CONTACT_OUT lets PUFF_IDS splits run in parallel; merge them into face-contact.json afterwards.
fs.writeFileSync(process.env.FACE_CONTACT_OUT?path.resolve(root,process.env.FACE_CONTACT_OUT):path.join(dest,'face-contact.json'),JSON.stringify(report,null,2)+'\n');
if(failures.length){console.error(JSON.stringify(failures.slice(0,10),null,1));process.exit(1);}
console.log('Puff contact verified:',report.models.length,'models,',times.length,'times each');
