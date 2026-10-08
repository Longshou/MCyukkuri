/* Offline pose checks and actual-mesh comparisons. Never drives a browser. */
const fs=require('fs'),path=require('path'),vm=require('vm'),cp=require('child_process'),os=require('os');
const {RuntimePose,spliceFit}=require('../preview/review/pose.js');
// The game splices the placement fit before the motion revision; check the same meshes.
const fits=JSON.parse(fs.readFileSync(path.join(__dirname,'../src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'))).models;
const root=path.resolve(__dirname,'..'),out=path.join(root,'.local/motion-fix'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');fs.mkdirSync(out,{recursive:true});
const python=process.env.REVIEW_PYTHON||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe');
const revision=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/motion_corrections.json')));
cp.execFileSync(python,['-c',String.raw`
from PIL import Image
from pathlib import Path
r=Path.cwd();o=r/'.local/motion-fix'
for d in (r/'src/main/resources/assets/mcyukkuri/textures/entity').iterdir():
 if d.is_dir():
  for e in ['neutral','puff','angry','cheek_puff']:
   p=d/(e+'.png')
   if p.exists():(o/(d.name+'-'+e+'.rgba')).write_bytes(Image.open(p).convert('RGBA').tobytes())
`],{cwd:root});
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const crypto=require('crypto'),manifest=JSON.parse(fs.readFileSync(path.join(root,'art/source/motion-corrections/manifest.json')));
const report={createdAt:new Date().toISOString(),scope:'Offline geometry and motion; no browser or live game acceptance',
 manifest:'art/source/motion-corrections/manifest.json',motionResourceSha256:manifest.runtime_sha256,
 verificationScriptSha256:crypto.createHash('sha256').update(fs.readFileSync(__filename)).digest('hex'),
 sourceModelSha256:Object.fromEntries(Object.entries(manifest.models).map(([id,m])=>[id,m.sha256])),models:[],comparisons:[]},cache={};
function minimum(data,model){let min=Infinity;for(let k=0;k<data.length;k+=24){if(Math.hypot(data[k+5],data[k+6],data[k+7])<.5)continue;for(let j=0;j<3;j++)min=Math.min(min,data[k+j*8+1]);}return min;}
for(const [id,rev] of Object.entries(revision.models)){
 const model=spliceFit(JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',id+'.json'))),fits[id]||null),scale=id.includes('deibu')?1.5:1;
 const pose=new RuntimePose(model,scale,rev),rest=Array.from(pose.frame('idle',0)),floor=minimum(rest,model);let frames=0,maxContactError=0;
 for(const [action,clip] of Object.entries(rev.clips)){
  for(let i=0;i<=16;i++){
   const t=clip.duration*i/16,data=pose.frame(action,t);if(!data.every(Number.isFinite))throw Error('Nonfinite '+id+'/'+action);frames++;
   const gap=minimum(data,model)-floor;
   if(gap < -.025*scale)throw Error('Below floor '+id+'/'+action+': '+gap);
   if(!['hop','taiso'].includes(action))maxContactError=Math.max(maxContactError,Math.abs(gap));
  }
 }
 if(maxContactError>.035*scale)throw Error('Floating support '+id+': '+maxContactError);
 const apex=minimum(pose.frame('hop',.4),model)-floor;
 if(Math.abs(apex-rev.hop_height*scale)>.001)throw Error('Wrong jump height '+id);
 if(pose.expressionFor('puff',1)==='neutral'||pose.expressionFor('taiso',11)==='neutral'||pose.expressionFor('taiso',8)!=='neutral')throw Error('Expression timing '+id);
 // A single invertible root transform must preserve all relative attachment
 // positions. No source branch may be independently displaced or stretched.
 for(const action of ['side_bend','taiso']){
  const t=action==='taiso'?3:pose.clips[action].duration*.25;
  const data=pose.frame(action,t),clip=pose.clips[action],values={};
  for(const track of clip.tracks){let index=0;while(index+1<track.keys.length&&track.keys[index+1][0]<t)index++;
   const a=track.keys[index],b=track.keys[Math.min(index+1,track.keys.length-1)],q=a[0]===b[0]?0:(t-a[0])/(b[0]-a[0]);values[track.channel]=a.slice(1).map((v,i)=>v+(b[i+1]-v)*q);}
  const pivot=model.bones[clip.tracks[0].bone].pivot,sz=Math.sin(values.rotation[2]*Math.PI/180),cz=Math.cos(values.rotation[2]*Math.PI/180);
  const sy=Math.sin(values.rotation[1]*Math.PI/180),cy=Math.cos(values.rotation[1]*Math.PI/180);
  for(const part of model.parts){if(part.anatomy||part.name.startsWith('puff_'))continue;
   for(let k=part.start*8;k<(part.start+part.count)*8;k+=8){
    if(Math.hypot(rest[k+5],rest[k+6],rest[k+7])<.5)continue;
    const x=(rest[k]/scale-pivot[0])*values.scale[0],y=(rest[k+1]/scale-pivot[1])*values.scale[1],z=(rest[k+2]/scale-pivot[2])*values.scale[2];
    const xx=cy*x+sy*z,zz=-sy*x+cy*z,expected=[cz*xx-sz*y,sz*xx+cz*y,zz];
    for(let j=0;j<3;j++)if(Math.abs(data[k+j]-(expected[j]+pivot[j]+values.position[j])*scale)>.00001)throw Error('Attachment drift '+id+'/'+action+'/'+part.name);
   }
  }
  // Static bounds at return-to-rest must exactly recover across action switches.
  const reset=pose.frame('idle',0);let err=0;for(let k=0;k<rest.length;k++)if(k%8<3)err=Math.max(err,Math.abs(rest[k]-reset[k]));
  if(err>1e-7)throw Error('Pose leakage '+id);
 }
 cache[id]={model,pose,scale,rev};report.models.push({id,frames,maxContactError,hopHeight:apex,bodyHeight:rev.body_height*scale});console.log(id,frames,'frames');
}
function render(id,action,t,current,az=0,width=320,height=300){
 const {model,scale,rev}=cache[id],pose=new RuntimePose(model,scale,current?rev:null);
 const expr=current?pose.expressionFor(action,t):'neutral';pose.expression(expr);
 const texture=fs.readFileSync(path.join(out,id+'-'+expr+'.rgba'));
 const neutral=pose.frame('idle',0);let max=0,radius=0;
 for(let i=0;i<neutral.length;i+=8){max=Math.max(max,neutral[i+1]);radius=Math.max(radius,Math.hypot(neutral[i],neutral[i+2]));}
 const span=Math.max(max*.7,radius*1.05,5),centerY=max*.52;
 return Buffer.from(raster.renderModel(pose.frame(action,t),texture,width,height,az,.12,span,{centerY,textureSize:model.texture_size||128}));
}
const cases=[['reimu','puff',1.6,0],['reimu','puff',1.6,Math.PI],['marisa','taiso',3.0,.65],['patchouli','side_bend',.59,.65],['reimu','hop',.4,0],['house_reimu','puff',1.6,0],['reimu_wasa_deibu','side_bend',.59,Math.PI],['marisa_bean','puff',1.6,.3]];
for(let i=0;i<cases.length;i++){
 const [id,a,t,az]=cases[i];for(const current of [false,true]){
  const tt=!current&&a==='hop'?.06544984695:t;
  fs.writeFileSync(path.join(out,`compare-${i}-${current?1:0}.rgba`),render(id,a,tt,current,az));
 }report.comparisons.push({id,action:a,time:t,az});
}
// Show the same floor/framing for the full animation, never recenter mid-jump.
for(let i=0;i<32;i++)for(const [a,d]of [['puff',6.4],['side_bend',2.36],['hop',.8],['taiso',14]])
 fs.writeFileSync(path.join(out,`motion-${a}-${i}.rgba`),render('reimu',a,d*i/32,true,.45,256,256));
fs.writeFileSync(path.join(out,'checks.json'),JSON.stringify(report,null,2));
cp.execFileSync(python,['-c',String.raw`
from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
import json
r=Path.cwd();o=r/'.local/motion-fix';dest=r/'preview/review/motion-fix';dest.mkdir(exist_ok=True)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',14)
report=json.loads((o/'checks.json').read_text());sheet=Image.new('RGB',(1280,1440),'#f5f3ed');draw=ImageDraw.Draw(sheet)
labels=['灵梦 · 鼓腮正面','灵梦 · 鼓腮背面','魔理沙 · 完整体操','帕秋莉 · 侧弯','灵梦 · 跳跃最高点','家灵梦 · 鼓腮','多毛灵胖 · 侧弯背面','豆形魔理沙 · 鼓腮']
for i,row in enumerate(report['comparisons']):
 x=(i%2)*640;y=(i//2)*360
 for j in range(2):
  im=Image.frombytes('RGBA',(320,300),(o/f'compare-{i}-{j}.rgba').read_bytes());sheet.paste(im,(x+j*320,y+32),im)
  draw.text((x+j*320+15,y+332),'修正前' if j==0 else '本轮修正',font=small,fill='#63746d')
 draw.text((x+15,y+8),labels[i],font=font,fill='#243e34')
sheet.save(dest/'comparison.png')
frames=[]
for i in range(32):
 im=Image.new('RGB',(1024,292),'#f5f3ed');d=ImageDraw.Draw(im)
 for j,(action,label) in enumerate([('puff','鼓腮'),('side_bend','侧弯'),('hop','跳动'),('taiso','完整体操')]):
  p=Image.frombytes('RGBA',(256,256),(o/f'motion-{action}-{i}.rgba').read_bytes());im.paste(p,(j*256,30),p);d.text((j*256+16,8),label,font=font,fill='#243e34')
 frames.append(im)
frames[0].save(dest/'motions.gif',save_all=True,append_images=frames[1:],duration=90,loop=0)
for action,duration in [('puff',6.4),('side_bend',2.36),('hop',.8),('taiso',14)]:
 imgs=[]
 for i in range(32):
  bg=Image.new('RGB',(256,256),'#f5f3ed');im=Image.frombytes('RGBA',(256,256),(o/f'motion-{action}-{i}.rgba').read_bytes());bg.paste(im,(0,0),im);imgs.append(bg)
 imgs[0].save(dest/(action+'.gif'),save_all=True,append_images=imgs[1:],duration=round(duration*1000/32/10)*10,loop=0)
(dest/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
`],{cwd:root,stdio:'inherit'});
console.log('Verified',report.models.length,'models;',report.models.reduce((a,b)=>a+b.frames,0),'frames');
