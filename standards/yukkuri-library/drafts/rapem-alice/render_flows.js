'use strict';
// Render state flows and family development using current meshes, placement and clips.
const fs=require('fs'),path=require('path'),vm=require('vm');
const {decode,encode}=require('./png.js');
const {SCENES,sampleScene}=require('./flow-scenes.js');
const {buildHeatClips}=require('./motion-clips.js');
const ROOT=path.resolve(__dirname,'../../../..'),ASSET=path.join(ROOT,'src/main/resources/assets/mcyukkuri');
const OUT=path.join(ROOT,'.local/previews/rapem-alice'),DIR=path.join(OUT,'flow-frames');
fs.mkdirSync(DIR,{recursive:true});
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const {RuntimePose}=require(path.join(ROOT,'preview/review/pose.js'));
const raster={};vm.runInNewContext(fs.readFileSync(path.join(ROOT,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const revisions=read(path.join(ASSET,'yukkuri_presentation/motion_corrections.json')).models;
const fits=read(path.join(ASSET,'yukkuri_presentation/placement_fit.json')).models;
const family=read(path.join(ASSET,'yukkuri_presentation/family.json'));
const poses={},textures={};
for(const id of ['alice','reimu']){
 const model=read(path.join(ASSET,'yukkuri',id+'.json'));
 const pose=new RuntimePose(model,1,revisions[id],fits[id]);
 if(id==='alice')Object.assign(pose.clips,buildHeatClips(model,{variant:'alice_rapem'}));
 poses[id]=pose;
}
const fruitPose=new RuntimePose(read(path.join(ASSET,'yukkuri/reimu.json')),1,revisions.reimu,fits.reimu);
function texture(id,face){
 const key=id+'/'+face;if(textures[key])return textures[key];
 const local=id==='alice'&&['breeding_period','alice_agitated','state_recover'].includes(face);
 const file=local?path.join(__dirname,'variant',face+'.png'):path.join(ASSET,'textures/entity',id,face+'.png');
 if(!fs.existsSync(file))throw Error('Missing face '+file);
 return textures[key]=decode(file);
}
const W=960,H=480,SPAN=15,CENTER=8,AZ=0,EL=.07;
const clamp=v=>Math.max(0,Math.min(1,v));
function atlasCopy(atlas,texture,slot){
 const ox=slot%2*256,oy=Math.floor(slot/2)*256;
 for(let y=0;y<256;y++)for(let x=0;x<256;x++){
  const s=(Math.floor(y*texture.h/256)*texture.w+Math.floor(x*texture.w/256))*4;
  const d=((oy+y)*512+ox+x)*4;for(let k=0;k<4;k++)atlas[d+k]=texture.data[s+k];
 }
}
function transform(vertices,actor,slot,lift=0){
 const out=Float64Array.from(vertices),angle=actor.yaw*Math.PI/180,c=Math.cos(angle),s=Math.sin(angle),sy=actor.remains?.65:1;
 for(let k=0;k<out.length;k+=8){
  const x=out[k],z=out[k+2],nx=out[k+5],nz=out[k+7];
  out[k]=x*c+z*s+actor.x;out[k+1]=out[k+1]*sy+lift;out[k+2]=-x*s+z*c+(actor.z||0);
  out[k+5]=nx*c+nz*s;out[k+7]=-nx*s+nz*c;
  if(sy!==1){out[k+6]/=sy;const n=Math.hypot(out[k+5],out[k+6],out[k+7]);for(let j=5;j<8;j++)out[k+j]/=n;}
  if(out[k+3]>=0){out[k+3]=(out[k+3]+slot%2)/2;out[k+4]=(out[k+4]+Math.floor(slot/2))/2;}
 }
 return out;
}
function triangle(out,a,b,c){
 const ab=b.map((v,i)=>v-a[i]),ac=c.map((v,i)=>v-a[i]);
 let n=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]];
 const l=Math.hypot(...n)||1;n=n.map(v=>v/l);for(const p of [a,b,c])out.push(...p,-1,0,...n);
}
function stemMesh(pose,id,progress,spent=false){
 // FamilyGeometry.arc and FamilyRenderer.renderStem: zero progress, growing or spent.
 if(!Number.isFinite(progress)||progress<0||progress>1)throw Error('Expected explicit STEM progress in [0,1]');
 if(progress===0&&!spent)return [];
 const count=3,variant=family.variants[id],stem=family.stem,root=variant.root;
 const q=spent?1:clamp(progress/.15),growth=q*q*(3-2*q);
 const points=stem.centerline.map(([z,y])=>{
  const rise=(y-stem.reference_origin[1])*stem.reference_units_to_model*family.lengthScale[count-1];
  const clearance=variant.depth_clearance*clamp(rise/variant.clearance_rise);
  return pose.rootPoint([root[0],root[1]+rise*growth,root[2]+(z*stem.reference_units_to_model*family.lengthScale[count-1]-clearance)*growth]);
 });
 const out=[];
 for(let i=0;i<points.length-1;i++){
  const a=points[i],b=points[i+1],dy=b[1]-a[1],dz=b[2]-a[2],l=Math.hypot(dy,dz)||1;
  const ring=(p,index,j)=>{const r=(stem.radius[0]+(stem.radius[1]-stem.radius[0])*index/(points.length-1))*family.radiusScale[count-1];const t=j*Math.PI/4;return [p[0]+r*Math.cos(t),p[1]-dz/l*r*Math.sin(t),p[2]+dy/l*r*Math.sin(t)];};
  for(let j=0;j<8;j++){const p=ring(a,i,j),q=ring(a,i,j+1),r=ring(b,i+1,j+1),s=ring(b,i+1,j);triangle(out,p,q,r);triangle(out,p,r,s);}
 }
 if(spent)for(let k=3;k<out.length;k+=8)out[k]=-2;
 return out;
}
function fruitMesh(mother,id,progress){
 // FamilyGeometry.socket/childBase + GrowthSize.fruit; current reimu sleep/idle mesh.
 const out=[],count=3,stem=family.stem,root=family.variants[id].root,parent=family.variants[id];
 const fruit=family.growth.fruit,scale=fruit.from+(fruit.to-fruit.from)*clamp((progress-fruit.visible_from)/(fruit.full_at-fruit.visible_from));
 fruitPose.expression('sleep');const posed=fruitPose.frame('idle',0);
 const origin=mother.rootPoint([0,0,0]);
 for(const [index,[z,y]] of stem.socket_layouts[String(count)].entries()){
  const rise=(y-stem.reference_origin[1])*stem.reference_units_to_model*family.lengthScale[count-1];
  const socket=[root[0],root[1]+rise,root[2]+z*stem.reference_units_to_model*family.lengthScale[count-1]-parent.depth_clearance*clamp(rise/parent.clearance_rise)];
  const base=[socket[0],socket[1]-stem.pendant_length_reference*stem.reference_units_to_model-family.variants.reimu.crown_height*scale,socket[2]-family.fruitForwardClearance];
  const head=[base[0],base[1]+family.variants.reimu.crown_height*scale,base[2]];
  const a=mother.rootPoint(socket),b=mother.rootPoint(head),axis=b.map((v,i)=>v-a[i]);
  const len=Math.hypot(...axis),n=axis.map(v=>v/len),ref=Math.abs(n[2])<.9?[0,0,1]:[1,0,0];
  const cross=(v,w)=>[v[1]*w[2]-v[2]*w[1],v[2]*w[0]-v[0]*w[2],v[0]*w[1]-v[1]*w[0]];
  let u=cross(n,ref);u=u.map(v=>v/Math.hypot(...u));const v=cross(n,u);
  const radius=Math.min(.12,(stem.radius[0]+(stem.radius[1]-stem.radius[0])*(index+.5)/count)*family.radiusScale[count-1]*.6);
  const ring=(p,r,j)=>p.map((value,k)=>value+r*(u[k]*Math.cos(j*Math.PI/4)+v[k]*Math.sin(j*Math.PI/4)));
  for(let j=0;j<8;j++){const p=ring(a,radius,j),q=ring(a,radius,j+1),r=ring(b,radius*.78,j+1),s=ring(b,radius*.78,j);triangle(out,p,q,r);triangle(out,p,r,s);triangle(out,a,q,p);triangle(out,b,s,r);}
  for(const part of fruitPose.model.parts)if(!part.anatomy&&!/^(peni_taper|mamu_)|tongue/.test(part.name)){
   for(let k=part.start*8;k<(part.start+part.count)*8;k+=8){
    const p=mother.rootPoint([base[0]+posed[k]*scale,base[1]+posed[k+1]*scale,base[2]+posed[k+2]*scale]);
    const raw=mother.rootPoint([posed[k+5],posed[k+6],posed[k+7]]).map((v,i)=>v-origin[i]),length=Math.hypot(...raw)||1;
    out.push(...p,posed[k+3],posed[k+4],...raw.map(v=>v/length));
   }
  }
 }
 return out;
}
function render(state,file,front=false){
 const chunks=[],atlas=Buffer.alloc(512*512*4);
 for(let slot=0;slot<state.actors.length;slot++){
  const actor=state.actors[slot],pose=poses[actor.id];
  if(!pose.clips[actor.action])throw Error('Missing source action '+actor.id+'/'+actor.action);
  const face=actor.face||pose.expressionFor(actor.action,actor.seconds,'neutral');
  pose.expression(face);const posed=pose.frame(actor.action,actor.seconds),visible=[];
  for(const part of pose.model.parts)if(!part.anatomy&&!/^(peni_taper|mamu_)|tongue/.test(part.name)){
   for(let k=part.start*8;k<(part.start+part.count)*8;k++)visible.push(posed[k]);
  }
  let minY=Infinity;for(let i=1;i<visible.length;i+=8)minY=Math.min(minY,visible[i]);
  // RemainsRenderer uses idle(0), sleep and Y scale .65, with no family attachment.
  const lift=Math.max(0,.02-minY*(actor.remains?.65:1));chunks.push(transform(visible,actor,slot,lift));
  if(!actor.remains&&actor.stemProgress>0)chunks.push(transform(stemMesh(pose,actor.id,actor.stemProgress,actor.spent),actor,slot,lift));
  if(actor.development&&actor.stemProgress>=family.growth.fruit.visible_from&&!actor.spent){
   chunks.push(transform(fruitMesh(pose,actor.id,actor.stemProgress),actor,slot+1,lift));atlasCopy(atlas,texture('reimu','sleep'),slot+1);
  }
  atlasCopy(atlas,texture(actor.id,face),slot);
 }
 const vertices=new Float64Array(chunks.reduce((n,v)=>n+v.length,0));let cursor=0;for(const v of chunks){vertices.set(v,cursor);cursor+=v.length;}
 const width=front?600:W,height=front?440:H,span=front?10.3:SPAN,center=front?4.8:CENTER;
 const pixels=raster.renderModel(vertices,atlas,width,height,0,front?0:EL,span,{centerY:center,textureSize:512,colors:[family.stem.material.stem,family.stem.material.dry]});
 const canvas=Buffer.alloc(width*height*4),scale=height/(2*span),floor=height/2+center*scale;
 for(let y=0;y<height;y++)for(let x=0;x<width;x++){
  const i=(y*width+x)*4;let shade=0;
  for(const actor of state.actors)shade+=Math.exp(-(((x-width/2-actor.x*scale)/(6.5*scale))**2+((y-floor)/(scale*.45))**2))*.22;
  let color=[240,245,240].map(v=>v*(1-Math.min(.3,shade)));
  if(Math.abs(y-floor)<.7)color=[173,190,176];
  const a=pixels[i+3]/255;for(let k=0;k<3;k++)canvas[i+k]=color[k]*(1-a)+pixels[i+k]*a;canvas[i+3]=255;
 }
 encode(width,height,canvas,file);
}
const FPS=10,samples=process.argv.includes('--samples'),developmentOnly=process.argv.includes('--development-only'),only=process.argv.find(a=>a.startsWith('--scene='))?.slice(8);
if(only&&!SCENES[only])throw Error('Unknown scene '+only);
// A single-scene refresh preserves metadata and renders for the other current scene.
const metadata=only||developmentOnly?read(path.join(OUT,'flow-frame-data.json')):{fps:FPS,scenes:SCENES,frames:{}};
if(metadata.fps!==FPS)throw Error('Refresh all scenes after changing FPS');metadata.scenes=SCENES;
for(const [name,scene] of Object.entries(SCENES)){
 if(only&&name!==only)continue;
 if(developmentOnly){metadata.frames[name]=metadata.frames[name].map(s=>({frame:s.frame,...sampleScene(name,s.t)}));continue;}
 const times=samples?scene.samples:Array.from({length:scene.duration*FPS+1},(_,i)=>i/FPS);
 metadata.frames[name]=[];
 for(const t of times){const state=sampleScene(name,t),frame=Math.round(t*FPS);render(state,path.join(DIR,`${name}-${String(frame).padStart(3,'0')}.png`));metadata.frames[name].push({frame,...state});}
 console.log('Rendered',name,times.length,'frames');
}
metadata.development=[
 {progress:0,label:'进度0 · 尚无茎',note:'胎次刚建立，茎从0开始。'},
 {progress:.06,label:'进度6% · 萌茎',note:'茎长随实际发育进度增长。'},
 {progress:.15,label:'进度15% · 茎果开始可见',note:'沿用当前茎果挂点、sleep表情和idle姿态。'},
 {progress:.55,label:'进度55% · 茎果发育',note:'营养仍足够时，继续发育。'},
 {progress:.95,label:'进度95% · 茎果成熟',note:'沿用现有茎果成长比例与挂接几何。'},
 {progress:0,label:'出生后 · 茎已离身',note:'出生完成后头顶无茎；茎按现有掉落机制离身。'}
];
for(let i=0;i<metadata.development.length;i++){
 const step=metadata.development[i];render({actors:[{id:'reimu',x:0,z:0,yaw:-35,action:'idle',seconds:0,face:'teary',stemProgress:step.progress,development:true}]},path.join(DIR,'stem-'+i+'.png'));
}
console.log('Rendered family development',metadata.development.length,'samples');
for(const face of ['breeding_period','alice_agitated','state_recover'])render({actors:[{id:'alice',x:0,z:0,yaw:0,action:'idle',seconds:0,face}]},path.join(DIR,'face-'+face+'.png'),true);
fs.writeFileSync(path.join(OUT,'flow-frame-data.json'),JSON.stringify(metadata,null,2)+'\n');
