/* Puff morph regression and current previews (v7). Offline; never drives a browser or the game.

   Checks every model: the refined skin is closed and lies on the original body at
   rest, facial features and every part outside the morph keep their exact posed
   position through the puff, the root never moves, side-lock swings are rigid,
   the envelope stays within [0, 1] and returns to rest, and idle after any puff
   frame is identical to the base model. Writes previews and checks.json. */
const fs=require('fs'),path=require('path'),vm=require('vm'),cp=require('child_process'),os=require('os'),crypto=require('crypto');
const {RuntimePose,puffAmount,spliceFit}=require('../preview/review/pose.js');
// The game splices the placement fit before the motion revision; check the same meshes.
const fits=JSON.parse(fs.readFileSync(path.join(__dirname,'../src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'))).models;
const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const out=path.join(root,process.env.PUFF_WORK||'.local/puff-v7/revision');fs.mkdirSync(out,{recursive:true});
const destination=path.join(root,'art/source/motion-corrections/previews/current');fs.mkdirSync(destination,{recursive:true});
const reportDirectory=path.join(root,'art/source/motion-corrections/reports/current');fs.mkdirSync(reportDirectory,{recursive:true});
const python=process.env.REVIEW_PYTHON||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe');
const runtime=path.join(assets,'yukkuri_presentation/motion_corrections.json'),motion=JSON.parse(fs.readFileSync(runtime));
if(motion.format!==2)throw Error('Expected motion corrections format 2');
const revisions=motion.models,manifest=JSON.parse(fs.readFileSync(path.join(root,'art/source/motion-corrections/manifest.json')));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const report={createdAt:new Date().toISOString(),scope:'Offline mesh and morph regression with offline renders; no live game or browser acceptance',
 runtimeSha256:hash(runtime),manifestRuntimeSha256:manifest.runtime_sha256,poseSha256:hash(path.join(root,'preview/review/pose.js')),
 javaModelSha256:hash(path.join(root,'src/main/java/io/github/longshou/mcyukkuri/client/YukkuriModel.java')),scriptSha256:hash(__filename),models:[]};
if(report.runtimeSha256!==report.manifestRuntimeSha256)throw Error('Runtime resource differs from manifest; re-export');
function fail(message){throw Error(message);}
const key=(v,k)=>[0,1,2].map(a=>Math.round(v[k+a]*1e4)).join(',');
const cache={};
for(const [id,rev] of Object.entries(revisions)){
 const model=spliceFit(JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',id+'.json'))),fits[id]||null),scale=id.includes('deibu')?1.5:1;
 const pose=new RuntimePose(model,scale,rev),base=new RuntimePose(model,scale,null),V=pose.model.vertices,surface=pose.puffSurface;
 // Closed refined skin.
 const edges=new Map();
 for(let k=surface.start*8;k<(surface.start+surface.count)*8;k+=24){const c=[0,1,2].map(j=>key(V,k+j*8));
  for(let j=0;j<3;j++){const a=c[j],b=c[(j+1)%3];if(a===b)continue;const e=a<b?a+'/'+b:b+'/'+a;edges.set(e,(edges.get(e)||0)+1);}}
 for(const [e,n] of edges)if(n!==2)fail(`${id}: refined skin edge ${e} used ${n} times`);
 // At rest the refined skin lies on the original body triangles.
 const body=model.parts[rev.puff_surface.part_index],planes=[];
 for(let k=body.start*8;k<(body.start+body.count)*8;k+=24){const a=[V[k],V[k+1],V[k+2]],b=[V[k+8],V[k+9],V[k+10]],c=[V[k+16],V[k+17],V[k+18]];
  const n=[(b[1]-a[1])*(c[2]-a[2])-(b[2]-a[2])*(c[1]-a[1]),(b[2]-a[2])*(c[0]-a[0])-(b[0]-a[0])*(c[2]-a[2]),(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])],l=Math.hypot(...n);
  if(l>1e-12)planes.push({a,b,c,n:n.map(x=>x/l)});}
 let offSurface=0;
 for(let k=surface.start*8;k<(surface.start+surface.count)*8;k+=8){const p=[V[k],V[k+1],V[k+2]];let best=Infinity;
  for(const t of planes){const d=Math.abs((p[0]-t.a[0])*t.n[0]+(p[1]-t.a[1])*t.n[1]+(p[2]-t.a[2])*t.n[2]);if(d>=best)continue;
   // inside test by barycentrics on the plane
   const v0=[t.b[0]-t.a[0],t.b[1]-t.a[1],t.b[2]-t.a[2]],v1=[t.c[0]-t.a[0],t.c[1]-t.a[1],t.c[2]-t.a[2]],v2=[p[0]-t.a[0],p[1]-t.a[1],p[2]-t.a[2]];
   const d00=v0[0]*v0[0]+v0[1]*v0[1]+v0[2]*v0[2],d01=v0[0]*v1[0]+v0[1]*v1[1]+v0[2]*v1[2],d11=v1[0]*v1[0]+v1[1]*v1[1]+v1[2]*v1[2],d20=v2[0]*v0[0]+v2[1]*v0[1]+v2[2]*v0[2],d21=v2[0]*v1[0]+v2[1]*v1[1]+v2[2]*v1[2];
   const den=d00*d11-d01*d01,v=(d11*d20-d01*d21)/den,w=(d00*d21-d01*d20)/den;if(v>=-1e-4&&w>=-1e-4&&v+w<=1+1e-4)best=d;}
  offSurface=Math.max(offSurface,best);}
 if(!(offSurface<1e-4))fail(`${id}: refined skin leaves the rest body by ${offSurface}`);
 // Root never moves during the puff, and the envelope behaves.
 for(const tr of rev.clips.puff.tracks)if(model.bones[tr.bone].name==='root')for(const k of tr.keys)for(let i=1;i<4;i++)if(Math.abs(k[i]-(tr.channel==='scale'?1:0))>1e-6)fail(`${id}: puff moves the root`);
 const clip=pose.clips.puff;let peak=0;for(let i=0;i<=640;i++){const a=puffAmount(clip,clip.duration*i/640);if(a<-1e-9||a>1+1e-9)fail(`${id}: envelope ${a}`);peak=Math.max(peak,a);}
 if(peak<.95||puffAmount(clip,0)!==0)fail(`${id}: envelope peak ${peak}`);
 // Feature and remaining-part stability against the rest frame.
 pose.expression('cheek_puff');const rest=Float64Array.from(pose.frame('puff',0));
 const morphed=new Set([rev.puff_surface.part_index,...rev.puff_attachments.map(a=>a.part_index),...(rev.puff_offsets||[]).map(o=>o.part_index)]);
 let stable=0;
 for(const t of [.3,.9,1.6,3.2,4.8,6.0]){const data=pose.frame('puff',t);
  model.parts.forEach((p,i)=>{if(morphed.has(i)||p.anatomy)return;for(let k=p.start*8;k<(p.start+p.count)*8;k++)if(k%8<5){if(Math.abs(data[k]-rest[k])>1e-9)fail(`${id}: ${p.name} moved during the puff`);stable++;}});}
 // Side locks swing rigidly: pairwise distances inside each moved part are kept.
 let rigid=0;
 for(const o of rev.puff_offsets||[]){const p=model.parts[o.part_index];for(let i=0;i+1<p.count;i++){const a=(p.start+i)*8,b=(p.start+i+1)*8;
  const d0=Math.hypot(V[a]-V[b],V[a+1]-V[b+1],V[a+2]-V[b+2]),q=[0,1,2].map(x=>V[a+x]+o.offsets[i*3+x]-V[b+x]-o.offsets[i*3+3+x]),d1=Math.hypot(...q);
  if(Math.abs(d1-d0)>1e-3*Math.max(1,d0))fail(`${id}: lock ${p.name} is not rigid`);rigid++;}}
 // Interruption: idle after any puff frame equals the base model's idle.
 base.expression('neutral');const idle=base.frame('idle',0);
 for(const t of [.7,3.2,5.9]){pose.frame('puff',t);pose.expression('neutral');const again=pose.frame('idle',0);
  for(let k=0;k<idle.length;k++)if(Math.abs(again[k]-idle[k])>1e-9)fail(`${id}: idle differs after puff interruption`);pose.expression('cheek_puff');}
 // Visibility of the threat display (reported, not judged).
 const full=pose.frame('puff',3.2);let maxOffset=0,restHalf=0,grownHalf=0,front=0,grownFront=0;
 for(let k=surface.start*8;k<(surface.start+surface.count)*8;k+=8){const i=k/8;maxOffset=Math.max(maxOffset,Math.hypot(pose.puffOffsets[i*3],pose.puffOffsets[i*3+1],pose.puffOffsets[i*3+2])*scale);
  restHalf=Math.max(restHalf,Math.abs(V[k])*scale);grownHalf=Math.max(grownHalf,Math.abs(full[k]));front=Math.min(front,V[k+2]*scale);grownFront=Math.min(grownFront,full[k+2]);}
 cache[id]={pose,model,scale,rev};
 report.models.push({id,refinedEdges:edges.size,restSurfaceDeviation:offSurface,stableValues:stable,rigidChecks:rigid,cheekSize:rev.cheek.size,
  locks:rev.cheek.locks,maxOffset,widthRatio:grownHalf/restHalf,forwardGain:front-grownFront,baseSha256:manifest.models[id].sha256});
 console.log(id,'closed, on-body at rest, features fixed, recovered; widen',(grownHalf/restHalf).toFixed(3),'forward',(front-grownFront).toFixed(2));
}
fs.writeFileSync(path.join(reportDirectory,'checks.json'),JSON.stringify(report,null,2)+'\n');
if(process.argv.includes('--checks-only'))process.exit(0);
cp.execFileSync(python,['-c',String.raw`
import sys
from pathlib import Path
from PIL import Image
r=Path(sys.argv[1]);o=Path(sys.argv[2])
for d in (r/'textures/entity').iterdir():
 for name in ['neutral','cheek_puff']:
  p=d/(name+'.png')
  if p.is_file():(o/(d.name+'-'+name+'.texture')).write_bytes(Image.open(p).convert('RGBA').tobytes())
`,assets,out]);
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
function render(id,action,t,az,el,width,height,focus){const {pose,model,scale}=cache[id],expr=pose.expressionFor(action,t);pose.expression(expr);
 const v=model.vertices;let top=0,radius=0;for(let i=0;i<v.length;i+=8){top=Math.max(top,v[i+1]*scale);radius=Math.max(radius,Math.hypot(v[i],v[i+2])*scale);}
 let span=Math.max(top*.62,radius,4),centerY=top*.47;
 if(focus){const b=model.parts.find(p=>p.name==='face_blush_left');let bx=0,by=0;for(let i=b.start;i<b.start+b.count;i++){bx=Math.max(bx,v[i*8]);by+=v[i*8+1]/b.count;}span=bx*scale*1.7;centerY=(by+bx*.25)*scale;}
 const texture=fs.readFileSync(path.join(out,id+'-'+expr+'.texture'));
 return Buffer.from(raster.renderModel(pose.frame(action,t),texture,width,height,az,el,span,{centerY,textureSize:model.texture_size||128}));}
const write=(name,data)=>fs.writeFileSync(path.join(out,name),data);
for(const id of ['reimu','marisa','house_reimu','patchouli'])for(const [view,a,t,az,el] of [['neutral','idle',0,0,.1],['front','puff',3.2,0,.1],['three','puff',3.2,.7,.25],['side','puff',3.2,1.45,.1],['back','puff',3.2,3.14,.1]])write(id+'-'+view+'.rgba',render(id,a,t,az,el,320,320));
for(const id of ['alice','chen','flandre','reimu_bean','reimu_wasa','marisa_face'])for(const [view,az] of [['left',-1.45],['left-three',-.7],['right-three',.7],['right',1.45]])write(id+'-'+view+'.rgba',render(id,'puff',3.2,az,.2,320,320));
for(const id of Object.keys(revisions)){write('puff-'+id+'.rgba',render(id,'puff',3.2,.45,.25,256,256));write('focus-'+id+'.rgba',render(id,'puff',3.2,.35,.2,256,256,true));}
for(let i=0;i<48;i++)write('loop-'+i+'.rgba',render('reimu','puff',6.4*i/48,.45,.2,360,360));
for(const id of ['reimu','house_reimu','flandre','reimu_bean'])for(let i=0;i<32;i++)write(id+'-face-'+i+'.rgba',render(id,'puff',6.4*i/32,0,.1,360,360,true));
fs.writeFileSync(path.join(out,'checks.json'),JSON.stringify(report));
cp.execFileSync(python,['-c',String.raw`
import json,sys
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
o=Path(sys.argv[1]);dest=Path(sys.argv[2])
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20);small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
def tile(name,size):return Image.frombytes('RGBA',size,(o/name).read_bytes())
sheet=Image.new('RGB',(1600,1440),'#f5f3ed');draw=ImageDraw.Draw(sheet)
for y,id in enumerate(['reimu','marisa','house_reimu','patchouli']):
 for x,(view,label) in enumerate([('neutral','常态'),('front','满腮 · 正面'),('three','斜侧面'),('side','侧面'),('back','背面')]):
  im=tile(id+'-'+view+'.rgba',(320,320));sheet.paste(im,(x*320,y*360+35),im);draw.text((x*320+15,y*360+8),label,font=font,fill='#30463c')
sheet.save(dest/'review.png')
sheet=Image.new('RGB',(1280,2160),'#f5f3ed');draw=ImageDraw.Draw(sheet)
for y,id in enumerate(['alice','chen','flandre','reimu_bean','reimu_wasa','marisa_face']):
 for x,view in enumerate(['left','left-three','right-three','right']):
  im=tile(id+'-'+view+'.rgba',(320,320));sheet.paste(im,(x*320,y*360+35),im);draw.text((x*320+12,y*360+8),id+' / '+view,font=font,fill='#30463c')
sheet.save(dest/'contact-angles.png')
report=json.loads((o/'checks.json').read_text(encoding='utf-8'))
for name,prefix in [('all-models.png','puff-'),('close-ups.png','focus-')]:
 sheet=Image.new('RGB',(1280,1160),'#f5f3ed');draw=ImageDraw.Draw(sheet)
 for i,row in enumerate(report['models']):
  im=tile(prefix+row['id']+'.rgba',(256,256));x=(i%5)*256;y=(i//5)*290;sheet.paste(im,(x,y),im);draw.text((x+8,y+262),row['id'],font=small,fill='#30463c')
 sheet.save(dest/name)
frames=[]
for i in range(48):
 im=tile('loop-'+str(i)+'.rgba',(360,360));bg=Image.new('RGB',im.size,'#f5f3ed');bg.paste(im,(0,0),im);frames.append(bg)
frames[0].save(dest/'puff.gif',save_all=True,append_images=frames[1:],duration=133,loop=0)
frames=[]
for i in range(32):
 bg=Image.new('RGB',(1440,400),'#f5f3ed');d=ImageDraw.Draw(bg)
 for col,id in enumerate(['reimu','house_reimu','flandre','reimu_bean']):
  im=tile(id+'-face-'+str(i)+'.rgba',(360,360));bg.paste(im,(col*360,35),im);d.text((col*360+12,8),id,font=font,fill='#30463c')
 frames.append(bg)
frames[0].save(dest/'face-stability.gif',save_all=True,append_images=frames[1:],duration=200,loop=0)
`,out,destination],{stdio:'inherit'});
console.log('Puff revision verified:',report.models.length,'models');
