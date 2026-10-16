/* Placement fit regression and previews. Offline; never drives a browser or the game.

   For every runtime form, with the placement fit and motion revision applied as in YukkuriModel:
   - the fit matches its base fingerprint and touches no body, face, puff or anatomy part;
   - headwear covers: no other part shows through a hat, cap or bonnet (a point under the cover,
     outside every closed piece of it, that some upward view reaches), over every clip;
   - skin newly exposed where the base model showed fitted hair (category renders from ten views);
   - opaque eye, brow, mouth and blush texels newly hidden from the front by fitted parts;
   - seated bows touch the head (closest sample within 0.15) and their tails stay visible from behind
     (at least 90% of the base model's tail pixels over four rear views) at rest and in waddle, press,
     brace, hop and side_bend frames;
   - nothing sinks below the base model's lowest point at rest.
   Writes art/source/placement-fit/reports/current/checks.json and preview sheets unless --checks-only. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),cp=require('child_process'),os=require('os'),crypto=require('crypto');
const {RuntimePose}=require('../preview/review/pose.js');
const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const work=path.join(root,process.env.FIT_WORK||'.local/placement-fit/verify');fs.mkdirSync(work,{recursive:true});
const previews=path.join(root,'art/source/placement-fit/previews/current'),reports=path.join(root,'art/source/placement-fit/reports/current');
const python=process.env.REVIEW_PYTHON||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe');
const checksOnly=process.argv.includes('--checks-only'),steps=+(process.env.FIT_STEPS||8);
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const fitPath=path.join(assets,'yukkuri_presentation/placement_fit.json'),motionPath=path.join(assets,'yukkuri_presentation/motion_corrections.json');
const fits=JSON.parse(fs.readFileSync(fitPath)).models,revisions=JSON.parse(fs.readFileSync(motionPath)).models;
cp.execFileSync(python,[path.join(root,'scripts/build_review_catalog.py')],{cwd:root,stdio:'inherit'});
const ctx={window:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'.local/previews/models/catalog.js'),'utf8'),ctx);const catalog=ctx.window.REVIEW_CATALOG;

const categoryRaster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),categoryRaster);
const sub=(a,b)=>[a[0]-b[0],a[1]-b[1],a[2]-b[2]],cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
function rayHit(p,d,T){const e1=sub(T[1],T[0]),e2=sub(T[2],T[0]),h=cross(d,e2),det=dot(e1,h);if(Math.abs(det)<1e-12)return -1;const f=1/det,s=sub(p,T[0]),u=f*dot(s,h);if(u<0||u>1)return -1;const q=cross(s,e1),v=f*dot(d,q);if(v<0||u+v>1)return -1;const t=f*dot(e2,q);return t>1e-6?t:-1;}
function tris(data,part){const out=[];for(let k=part.start*8;k<(part.start+part.count)*8;k+=24){const T=[[data[k],data[k+1],data[k+2]],[data[k+8],data[k+9],data[k+10]],[data[k+16],data[k+17],data[k+18]]];if(Math.hypot(...cross(sub(T[1],T[0]),sub(T[2],T[0]))) >1e-10)out.push(T);}return out;}
function components(ts){const key=p=>p.map(v=>Math.round(v*1e4)).join(','),parent=ts.map((_,i)=>i),find=i=>parent[i]===i?i:(parent[i]=find(parent[i])),seen=new Map();
 ts.forEach((T,i)=>T.forEach(p=>{const k=key(p);if(seen.has(k))parent[find(i)]=find(seen.get(k));else seen.set(k,i);}));const g=new Map();ts.forEach((T,i)=>{const r=find(i);if(!g.has(r))g.set(r,[]);g.get(r).push(T);});return [...g.values()];}
function bbox(ts){const lo=[1e9,1e9,1e9],hi=[-1e9,-1e9,-1e9];for(const T of ts)for(const p of T)for(let a=0;a<3;a++){lo[a]=Math.min(lo[a],p[a]);hi[a]=Math.max(hi[a],p[a]);}return {lo,hi};}
const inBox=(p,b)=>p[0]>=b.lo[0]&&p[0]<=b.hi[0]&&p[1]>=b.lo[1]&&p[1]<=b.hi[1]&&p[2]>=b.lo[2]&&p[2]<=b.hi[2];
function inside(p,ts){let n=0;const d=[0.5773,0.5774,0.5773];for(const T of ts)if(rayHit(p,d,T)>0)n++;return n%2===1;}
function samples(ts){const out=[];for(const T of ts){out.push(...T);out.push(T[0].map((v,a)=>(v+T[1][a]+T[2][a])/3));}return out;}
const DIRS=[[0,1,0],...Array.from({length:8},(_,k)=>{const a=k*Math.PI/4,e=0.6;return [Math.cos(a)*Math.cos(e),Math.sin(e),Math.sin(a)*Math.cos(e)];})];
const COVERS=[[/^(brim_|hat_|bow_)/,id=>id.includes('marisa')&&id!=='marisa_tsumuri'],[/^chen_hat_/,id=>id==='chen'],
 [/^patchouli_(bonnet|frill|side_frill|hat_ribbon|crescent)/,id=>id==='patchouli'],[/^predator_cap_/,id=>id==='remilia'||id==='flandre']];
const skipPart=p=>p.anatomy||/^face_|^puff_/.test(p.name);
/* Points of other parts visible through the cover. */
function pokes(pose,data,cover){
 const parts=pose.model.parts,coverIdx=[],others=[];parts.forEach((p,i)=>{if(skipPart(p))return;(cover.test(p.name)?coverIdx:others).push(i);});
 const coverTris=coverIdx.flatMap(i=>tris(data,parts[i]));if(!coverTris.length)return {};
 const pieces=coverIdx.flatMap(i=>components(tris(data,parts[i]))).map(ts=>({ts,b:bbox(ts)})),cb=bbox(coverTris),found={};
 for(const i of others)for(const p of samples(tris(data,parts[i]))){
  if(p[0]<cb.lo[0]||p[0]>cb.hi[0]||p[2]<cb.lo[2]||p[2]>cb.hi[2]||p[1]<cb.lo[1]-0.01)continue;
  if(!coverTris.some(T=>Math.min(T[0][1],T[1][1],T[2][1])<=p[1]&&rayHit(p,[0,-1,0],T)>0))continue;
  if(pieces.some(q=>inBox(p,q.b)&&inside(p,q.ts)))continue;
  if(!DIRS.some(d=>!coverTris.some(T=>rayHit(p,d,T)>0)))continue;
  found[parts[i].name]=(found[parts[i].name]||0)+1;}
 return found;
}
function closestOnTriangle(p,[a,b,c]){ // Ericson, Real-Time Collision Detection 5.1.5
 const ab=sub(b,a),ac=sub(c,a),ap=sub(p,a),d1=dot(ab,ap),d2=dot(ac,ap);if(d1<=0&&d2<=0)return a;
 const bp=sub(p,b),d3=dot(ab,bp),d4=dot(ac,bp);if(d3>=0&&d4<=d3)return b;const vc=d1*d4-d3*d2;if(vc<=0&&d1>=0&&d3<=0){const v=d1/(d1-d3);return a.map((x,k)=>x+ab[k]*v);}
 const cp_=sub(p,c),d5=dot(ab,cp_),d6=dot(ac,cp_);if(d6>=0&&d5<=d6)return c;const vb=d5*d2-d1*d6;if(vb<=0&&d2>=0&&d6<=0){const w=d2/(d2-d6);return a.map((x,k)=>x+ac[k]*w);}
 const va=d3*d6-d5*d4;if(va<=0&&(d4-d3)>=0&&(d5-d6)>=0){const w=(d4-d3)/((d4-d3)+(d5-d6));return b.map((x,k)=>x+(c[k]-x)*w);}
 const den=1/(va+vb+vc),v=vb*den,w=vc*den;return a.map((x,k)=>x+ab[k]*v+ac[k]*w);}
function texture(id,name){const file=path.join(work,`${id}-${name}.rgba`);
 if(!fs.existsSync(file))cp.execFileSync(python,['-c',`from PIL import Image;open(r"${file}","wb").write(Image.open(r"${path.join(assets,'textures/entity',id,name+'.png')}").convert("RGBA").tobytes())`]);
 return fs.readFileSync(file);}
/* Opaque face texels as model-space points (front faces only). */
function inkPoints(model,data,tex,size){const out=[];model.parts.forEach(p=>{if(!/^face_(eye|brow|mouth|blush)/.test(p.name))return;
 for(let k=p.start*8;k<(p.start+p.count)*8;k+=24){if(data[k+7]>-0.2)continue;const V=[0,1,2].map(j=>data.slice(k+j*8,k+j*8+5));
  const den=(V[1][4]-V[2][4])*(V[0][3]-V[2][3])+(V[2][3]-V[1][3])*(V[0][4]-V[2][4]);if(Math.abs(den)<1e-12)continue;
  const u0=Math.floor(Math.min(...V.map(v=>v[3]))*size),u1=Math.ceil(Math.max(...V.map(v=>v[3]))*size),v0=Math.floor(Math.min(...V.map(v=>v[4]))*size),v1=Math.ceil(Math.max(...V.map(v=>v[4]))*size);
  for(let y=v0;y<v1;y++)for(let x=u0;x<u1;x++){if(tex[(y*size+x)*4+3]<128)continue;const u=(x+.5)/size,v=(y+.5)/size;
   const wa=((V[1][4]-V[2][4])*(u-V[2][3])+(V[2][3]-V[1][3])*(v-V[2][4]))/den,wb=((V[2][4]-V[0][4])*(u-V[2][3])+(V[0][3]-V[2][3])*(v-V[2][4]))/den,wc=1-wa-wb;
   if(Math.min(wa,wb,wc)<-1e-6)continue;out.push([0,1,2].map(a=>wa*V[0][a]+wb*V[1][a]+wc*V[2][a]));}}});return out;}
function occluded(points,blockers){let n=0;const dirs=[[0,0,-1],[0.26,0.17,-0.95],[-0.26,0.17,-0.95]];
 for(const p of points){const q=[p[0],p[1],p[2]-0.02];if(dirs.some(d=>blockers.some(T=>rayHit(q,d,T)>0)))n++;}return n;}

const report={createdAt:new Date().toISOString(),
 scope:'Offline geometry regression of the placement fit with the motion revision applied, sampled every 1/'+steps+' of each clip; poke-through uses nine upward view directions. No Minecraft client, lighting or user confirmation.',
 placementFitSha256:hash(fitPath),motionSha256:hash(motionPath),poseSha256:hash(path.join(root,'preview/review/pose.js')),scriptSha256:hash(__filename),
 recipeSha256:hash(path.join(root,'standards/yukkuri-library/presentation/placement-fit/recipe.json')),models:[]};
const failures=[];const fail=(id,msg)=>{failures.push(id+': '+msg);};
for(const item of catalog.models){
 const id=item.id,file=path.join(root,item.url),model=JSON.parse(fs.readFileSync(file)),fit=fits[id]||null,rev=revisions[id];
 const row={id,fitted:!!fit};report.models.push(row);
 if(fit){if(fit.base_sha256!==hash(file))fail(id,'base fingerprint differs');
  for(const p of fit.parts)if(/body_surface|rump_mark|rear_mark|^face_|^puff_|mamu|peni|house_rump/.test(p.name))fail(id,'fit touches '+p.name);
  row.parts=fit.parts.length;row.refined=fit.parts.filter(p=>!p.inplace).length;row.pivots=fit.bone_pivots.map(b=>b.name);}
 const base=new RuntimePose(model,1,rev?{bone_names:rev.bone_names,clips:rev.clips}:null,null),pose=new RuntimePose(model,1,rev,fit);
 // Covers over every clip.
 const cover=COVERS.find(([,applies])=>applies(id));
 if(cover){let before=0,after=0;const where={};
  for(const [action,clip] of Object.entries(pose.clips)){if(action.startsWith('anatomy'))continue;
   for(let s=0;s<steps;s++){const t=clip.duration*s/steps;
    const a=pokes(pose,pose.frame(action,t),cover[0]),b=pokes(base,base.frame(action,t),cover[0]);
    const na=Object.values(a).reduce((x,y)=>x+y,0),nb=Object.values(b).reduce((x,y)=>x+y,0);after+=na;before+=nb;
    if(na>0)for(const [k,v] of Object.entries(a))where[action+'@'+t.toFixed(2)+':'+k]=v;}}
  row.coverPokes={base:before,fit:after,examples:Object.entries(where).slice(0,6)};
  if(after>before)fail(id,`cover poke-through ${after} > base ${before}`);}
 if(!fit){row.note='unchanged';continue;}
 const restFit=Float64Array.from(pose.frame('idle',0)),restBase=Float64Array.from(base.frame('idle',0));
 // Skin newly exposed where fitted hair used to be: hair pulled into the head would show skin through it.
 // Hair points inside the body are not counted directly; inner faces of the hair shell lie there by design.
 {const accessory=/^(bow_|brim_|hat_|chen_hat_|alice_band_|alice_lace_)/,fittedHair=new Set(fit.parts.map(e=>e.part_index).filter(i=>!accessory.test(model.parts[i].name)));
  const cat=(ps,data)=>{const d=Float64Array.from(data);ps.forEach((p,i)=>{const c=/body_surface|rump|rear_mark|^face_/.test(p.name)?0:fittedHair.has(i)?1:2;for(let k=p.start*8;k<(p.start+p.count)*8;k+=8)d[k+3]=-1-c;if(p.anatomy)d.fill(0,p.start*8,(p.start+p.count)*8);});return d;};
  const b=cat(model.parts,restBase),f=cat(pose.model.parts,restFit),opts={centerY:6,textureSize:1,colors:[[255,0,0],[0,255,0],[0,0,255]]};
  let skin=0,exposed=0;for(const [az,el] of [[0,.1],[.6,.15],[-.6,.15],[1.57,0],[-1.57,0],[2.5,.3],[-2.5,.3],[3.14,.1],[0,.7],[1.57,.6]]){
   const pb=categoryRaster.renderModel(b,new Uint8Array(4),200,200,az,el,11,opts),pf=categoryRaster.renderModel(f,new Uint8Array(4),200,200,az,el,11,opts);
   for(let i=0;i<40000;i++){const kind=(px,k)=>!px[k*4+3]?-1:px[k*4]>150?0:px[k*4+1]>150?1:2;const kb=kind(pb,i),kf=kind(pf,i);if(kb===0)skin++;if(kb===1&&kf===0)exposed++;}}
  // 0.5%: the lowered side-lock tops uncover a temple sliver under the rounded crown edge (face side, not skin through hair).
  row.exposedSkin={skinPixels:skin,exposedFromHair:exposed};if(exposed>skin*0.005)fail(id,`skin exposed where hair was: ${exposed} of ${skin}`);}
 // Face ink newly hidden from the front by fitted parts.
 const size=model.texture_size||128,tex=texture(id,'neutral'),ink=inkPoints(model,restBase,tex,size);
 const fitted=new Set(fit.parts.map(e=>e.part_index));
 const blockFit=[...fitted].flatMap(i=>tris(restFit,pose.model.parts[i])),blockBase=[...fitted].flatMap(i=>tris(restBase,model.parts[i]));
 row.faceInk={samples:ink.length,hiddenBase:occluded(ink,blockBase),hiddenFit:occluded(ink,blockFit)};
 if(row.faceInk.hiddenFit>row.faceInk.hiddenBase)fail(id,`face ink hidden ${row.faceInk.hiddenFit} > ${row.faceInk.hiddenBase}`);
 // Seated bows touch the head.
 const knot=pose.model.parts.map((p,i)=>/^bow_(knot|tail_)/.test(p.name)&&fitted.has(i)?i:-1).filter(i=>i>=0);
 if(knot.length){const target=pose.model.parts.map((p,i)=>!skipPart(p)&&!/^bow_/.test(p.name)?i:-1).filter(i=>i>=0).flatMap(i=>tris(restFit,pose.model.parts[i]));
  let best=Infinity;for(const p of samples(knot.flatMap(i=>tris(restFit,pose.model.parts[i]))))for(const T of target){const c=closestOnTriangle(p,T);best=Math.min(best,Math.hypot(...sub(p,c)));}
  row.bowGap=+best.toFixed(4);if(!(best<=0.15))fail(id,'bow gap '+best);}
 // Bow tails hanging on the back of the head must not sink into the hair or scalp.
 if(pose.model.parts.some(p=>/^bow_tail_/.test(p.name))){
  const tailPixels=(ps,data)=>{const d=Float64Array.from(data);ps.forEach(p=>{const c=/^bow_tail_/.test(p.name)?0:1;for(let k=p.start*8;k<(p.start+p.count)*8;k+=8)d[k+3]=-1-c;if(p.anatomy)d.fill(0,p.start*8,(p.start+p.count)*8);});
   let n=0;for(const [az,el] of [[3.14,.1],[2.6,.3],[-2.6,.3],[3.14,.5]]){const px=categoryRaster.renderModel(d,new Uint8Array(4),240,240,az,el,11,{centerY:7,textureSize:1,colors:[[255,0,0],[0,0,255]]});for(let i=0;i<57600;i++)if(px[i*4+3]&&px[i*4]>150)n++;}return n;};
  const clipsOnly=rev&&{bone_names:rev.bone_names,clips:rev.clips},bp=new RuntimePose(model,1,clipsOnly,null),fp=new RuntimePose(model,1,clipsOnly,fit);
  let worst=Infinity,where='';row.bowTails={};
  for(const [a,t] of [['idle',0],['waddle',.3],['waddle',.9],['press',1.2],['press',3.6],['brace',.6],['brace',1.2],['hop',.4],['side_bend',.6]]){
   const tb=tailPixels(bp.model.parts,bp.frame(a,t)),tf=tailPixels(fp.model.parts,fp.frame(a,t)),r=tf/Math.max(1,tb);row.bowTails[a+'@'+t]=+r.toFixed(3);if(r<worst){worst=r;where=a+'@'+t;}}
  if(worst<0.9)fail(id,`bow tails hidden at ${where}: ${worst.toFixed(3)} of the base rear pixels`);}
 let lowBase=Infinity,lowFit=Infinity;for(let k=0;k<restBase.length;k+=8)if(restBase[k]||restBase[k+1]||restBase[k+2])lowBase=Math.min(lowBase,restBase[k+1]);
 for(let k=0;k<restFit.length;k+=8)if(restFit[k]||restFit[k+1]||restFit[k+2])lowFit=Math.min(lowFit,restFit[k+1]);
 row.floor={base:+lowBase.toFixed(5),fit:+lowFit.toFixed(5)};if(lowFit<lowBase-1e-6)fail(id,'sinks below the base floor');
 console.log(id,JSON.stringify(row));
}
report.failures=failures;fs.mkdirSync(reports,{recursive:true});fs.writeFileSync(path.join(reports,'checks.json'),JSON.stringify(report,null,2)+'\n');
if(failures.length){console.error(failures.join('\n'));process.exitCode=1;}
if(!checksOnly)render();
function render(){
 const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
 fs.mkdirSync(previews,{recursive:true});const frames=[];
 const shot=(id,label,variant,action,t,az,el,W,hide)=>{const item=catalog.models.find(m=>m.id===id),model=JSON.parse(fs.readFileSync(path.join(root,item.url)));
  const clipsOnly=r=>r&&{bone_names:r.bone_names,clips:r.clips,expression_aliases:r.expression_aliases,expression_patches:r.expression_patches};
  const pose=variant==='fit'?new RuntimePose(model,item.formScale,revisions[id],fits[id]||null):new RuntimePose(model,item.formScale,clipsOnly(revisions[id]),null);
  const ref=new RuntimePose(model,item.formScale,clipsOnly(revisions[id]),null).frame('idle',0);
  let min=1e9,max=-1e9,r=0;for(let i=0;i<ref.length;i+=8){if(!ref[i]&&!ref[i+1]&&!ref[i+2])continue;min=Math.min(min,ref[i+1]);max=Math.max(max,ref[i+1]);r=Math.max(r,Math.hypot(ref[i],ref[i+2]));}
  pose.expression(pose.expressionFor(action,t,'neutral'));const v=pose.frame(action,t);
  if(hide)for(const p of pose.model.parts)if(hide.test(p.name))v.fill(0,p.start*8,(p.start+p.count)*8);
  const name=pose.expressionFor(action,t,'neutral'),tex=texture(id,item.textures[name]?name:'neutral');
  const px=raster.renderModel(v,tex,W,W,az,el,Math.max((max-min)*.62,r,4)+1.2,{centerY:(min+max)/2+.6,textureSize:item.textureSize});
  const f=path.join(work,'frame-'+frames.length+'.rgba');fs.writeFileSync(f,Buffer.from(px));frames.push({f,label});return frames.length-1;};
 const views=[[0,.1,'正面'],[.7,.15,'斜前'],[Math.PI/2,0,'侧面'],[2.6,.3,'斜后']];
 const sheets=[];
 const changed=Object.keys(fits),groups=[['before-after-reimu',changed.filter(id=>id.startsWith('reimu'))],['before-after-others',changed.filter(id=>!id.startsWith('reimu'))]];
 for(const [name,ids] of groups){const cells=[];for(const id of ids)for(const variant of ['base','fit'])for(const [az,el,label] of views)cells.push(shot(id,`${variant==='base'?'原':'新'} ${id} ${label}`,variant,'idle',0,az,el,220));sheets.push({name,cols:4,W:220,cells});}
 const hatOff=[];for(const id of ['marisa','marisa_face','marisa_sparse'])for(const variant of ['base','fit'])for(const [az,el,label] of views)hatOff.push(shot(id,`${variant==='base'?'原':'新'} ${id} 脱帽 ${label}`,variant,'idle',0,az,el,200,/^(brim_|hat_|bow_)/));
 sheets.push({name:'hat-off',cols:4,W:200,cells:hatOff});
 const acts=[['waddle',.3],['waddle',.9],['press',1.2],['brace',1.2],['hop',.4],['puff',3.2],['side_bend',.6],['taiso',11.2]],actionCells=[];
 for(const id of ['reimu','marisa','chen','alice','reimu_wasa','marisa_sparse'])for(const [a,t] of acts)actionCells.push(shot(id,`新 ${id} ${a}@${t}`,'fit',a,t,.6,.15,180));
 sheets.push({name:'actions',cols:8,W:180,cells:actionCells});
 const unchanged=[];for(const id of catalog.models.map(m=>m.id).filter(id=>!fits[id]))for(const [az,el,label] of views)unchanged.push(shot(id,`不变 ${id} ${label}`,'base','idle',0,az,el,200));
 sheets.push({name:'unchanged',cols:4,W:200,cells:unchanged});
 fs.writeFileSync(path.join(work,'sheets.json'),JSON.stringify({sheets,frames,out:previews}));
 cp.execFileSync(python,['-c',String.raw`
import json,sys
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
d=json.load(open(sys.argv[1],encoding='utf-8'));font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',13)
for s in d['sheets']:
 W,c=s['W'],s['cols'];rows=(len(s['cells'])+c-1)//c;sheet=Image.new('RGB',(W*c,(W+20)*rows),'#f5f3ed');dr=ImageDraw.Draw(sheet)
 for n,i in enumerate(s['cells']):
  f=d['frames'][i];im=Image.frombytes('RGBA',(W,W),Path(f['f']).read_bytes());x=(n%c)*W;y=(n//c)*(W+20)
  sheet.paste(im,(x,y),im);dr.text((x+4,y+W+2),f['label'],fill='#283a36',font=font)
 sheet.save(Path(d['out'])/(s['name']+'.png'))
`,path.join(work,'sheets.json')]);
 console.log('Previews written to '+path.relative(root,previews));
}
