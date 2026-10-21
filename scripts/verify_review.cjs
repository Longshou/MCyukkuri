/* Command-line data/pose verification and offline thumbnails; no browser automation. */
const fs=require('fs'),path=require('path'),vm=require('vm'),cp=require('child_process'),os=require('os');
const {RuntimePose,spliceFit}=require('../preview/review/pose.js');
// The game splices the placement fit before the motion revision; check the same meshes.
const fits=JSON.parse(fs.readFileSync(path.join(__dirname,'../src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'))).models;
const root=path.resolve(__dirname,'..'),out=path.join(root,'.local/reports/model-review');fs.mkdirSync(out,{recursive:true});
const bundledPython=path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/python',process.platform==='win32'?'python.exe':'bin/python3');
const python=process.env.REVIEW_PYTHON||(fs.existsSync(bundledPython)?bundledPython:process.platform==='win32'?'python':'python3');
cp.execFileSync(python,[path.join(root,'scripts/build_review_catalog.py')],{cwd:root,stdio:'inherit'});
const context={window:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'.local/previews/models/catalog.js'),'utf8'),context);const catalog=context.window.REVIEW_CATALOG;
const revisions=JSON.parse(fs.readFileSync(path.join(root,catalog.motions),'utf8')).models;
cp.execFileSync(python,['-c',String.raw`
from PIL import Image
from pathlib import Path
import json
r=Path.cwd();o=r/'.local/reports/model-review'
for p in (r/'src/main/resources/assets/mcyukkuri/textures/entity').glob('*/neutral.png'):
 im=Image.open(p).convert('RGBA');(o/(p.parent.name+'.texture')).write_bytes(im.tobytes())
`],{cwd:root,stdio:'inherit'});
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
cp.execFileSync(python,[path.join(root,'scripts/build_rounded_preview.py')],{cwd:root,stdio:'inherit'});
const legacy=fs.readFileSync(path.join(root,'.local/previews/rounded-runtime/preview.js'),'utf8');const legacyFunctions=legacy.slice(legacy.indexOf('function sample('),legacy.indexOf('function groundVertices('));
const report={models:[],links:0,audio:catalog.audio.length,referenceComparisons:[]};
for(const item of catalog.models){
 const model=spliceFit(JSON.parse(fs.readFileSync(path.join(root,item.url),'utf8')),fits[item.id]||null),pose=new RuntimePose(model,item.formScale,revisions[item.id]);
 model.clips={...model.clips,...revisions[item.id].clips};
 for(const url of Object.values(item.textures)){if(!fs.existsSync(path.join(root,url)))throw Error('Missing '+url);report.links++;}
 let frames=0;
 for(const [action,clip]of Object.entries(pose.clips))for(const fraction of [0,.5,.95]){
  const data=pose.frame(action,clip.duration*fraction);if(!data.every(Number.isFinite))throw Error('Nonfinite pose: '+item.id+'/'+action);frames++;
 }
 for(const expression of Object.keys(item.textures)){
  pose.expression(expression);
  const expected=Float64Array.from(model.vertices);
  // A revision's own named patch wins; otherwise the alias lends its geometry.
  const patches={...model.expression_patches,...revisions[item.id].expression_patches};
  const sourceExpression=patches[expression]?expression:revisions[item.id].expression_aliases?.[expression]||expression;
  for(const patch of patches[sourceExpression]||[]){const part=model.parts[patch.index];expected.set(patch.vertices,part.start*8);}
  for(let k=0;k<expected.length;k++)if(expected[k]!==pose.src[k])throw Error('Expression overwrote another part '+item.id+'/'+expression+'/'+k);
  if(!pose.frame('waddle',.3).every(Number.isFinite))throw Error('Invalid expression '+item.id+'/'+expression);frames++;
 }
 pose.expression('neutral');
 // Compare our optimized transform with the existing runtime preview algorithm.
 // Legacy preview omits Minecraft's house face-back rejection; compare other rigs.
 if(['reimu','marisa_bean','reimu_wasa_deibu','remilia'].includes(item.id)){
  model.clips={...model.clips,...model.behavior_clips};
  const ref={smoothHouseSurface:raster.smoothHouseSurface,MODELS:{[item.id]:{model,formScale:item.formScale}},$:id=>({value:id==='subject'?item.id:'neutral'}),clamp:(n,a,b)=>Math.max(a,Math.min(b,n))};vm.createContext(ref);vm.runInContext(legacyFunctions,ref);
  for(const action of ['idle','waddle','side_bend']){
   const expected=ref.posed(model,action,.35),actual=pose.frame(action,.35),compact=[];
   // Walk parts in order: placement-fit parts may live at the end of the vertex array.
   for(const part of pose.model.parts)for(let i=part.start*8;i<(part.start+part.count)*8;i+=24){if(Math.hypot(actual[i+5],actual[i+6],actual[i+7])<.5)continue;for(let j=0;j<24;j++)compact.push(actual[i+j]);}
   if(compact.length!==expected.length)throw Error('Reference vertex count mismatch '+item.id);
   let maxPositionError=0;for(let i=0;i<compact.length;i++)if(i%8<5)maxPositionError=Math.max(maxPositionError,Math.abs(compact[i]-expected[i]));
   if(maxPositionError>.002)throw Error('Reference pose mismatch '+item.id+' '+maxPositionError);
   report.referenceComparisons.push({id:item.id,action,maxPositionError});
  }
 }
 const vertices=pose.frame('idle',0);let min=Infinity,max=-Infinity,radius=0;for(let i=0;i<vertices.length;i+=8){min=Math.min(min,vertices[i+1]);max=Math.max(max,vertices[i+1]);radius=Math.max(radius,Math.hypot(vertices[i],vertices[i+2]));}
 const texture=fs.readFileSync(path.join(out,item.id+'.texture'));
 if(texture.length!==item.textureSize**2*4)throw Error('Texture dimension mismatch '+item.id);
 const pixels=raster.renderModel(vertices,texture,320,300,.45,.15,Math.max((max-min)*.66,radius*.95,4),{centerY:(min+max)/2,textureSize:item.textureSize});
 const visible=pixels.reduce((sum,n,i)=>sum+(i%4===3&&n>128?1:0),0);if(visible<500)throw Error('Empty rendering '+item.id);
 fs.writeFileSync(path.join(out,item.id+'.rgba'),Buffer.from(pixels));report.models.push({id:item.id,name:item.name,frames,visible,actions:Object.keys(pose.clips).length});console.log(item.id+': '+frames+' poses checked');
}
for(const sound of catalog.audio){if(!fs.existsSync(path.join(root,sound.url)))throw Error('Missing '+sound.url);report.links++;}
const html=fs.readFileSync(path.join(root,'preview/review/index.html'),'utf8');for(const match of html.matchAll(/(?:href|src)="(\/[^"]+)"/g)){if(!fs.existsSync(path.join(root,match[1])))throw Error('Missing link '+match[1]);report.links++;}
fs.writeFileSync(path.join(out,'checks.json'),JSON.stringify(report,null,2));
cp.execFileSync(python,['-c',String.raw`
from PIL import Image,ImageDraw
import sys
sys.path.insert(0,'scripts')
from build_preview_hub import font as chinese_font
from pathlib import Path
import json
r=Path.cwd();o=r/'.local/reports/model-review';thumbs=r/'.local/previews/models/thumbs';thumbs.mkdir(exist_ok=True)
rows=json.loads((o/'checks.json').read_text(encoding='utf-8'))['models'];sheet=Image.new('RGB',(1600,1416),'#f5f3ed');d=ImageDraw.Draw(sheet)
font=chinese_font(22);small=chinese_font(14)
for i,row in enumerate(rows):
 im=Image.frombytes('RGBA',(320,300),(o/(row['id']+'.rgba')).read_bytes());im.save(thumbs/(row['id']+'.png'))
 x=(i%5)*320;y=(i//5)*350;sheet.paste(im,(x,y),im);d.text((x+18,y+303),row['name'],fill='#283a36',font=font);d.text((x+18,y+332),str(row['actions'])+' 段动作',fill='#728079',font=small)
preview=r/'.local/previews/model-review';preview.mkdir(parents=True,exist_ok=True);sheet.save(preview/'contact-sheet.png')
`],{cwd:root,stdio:'inherit'});
console.log(JSON.stringify({models:report.models.length,frames:report.models.reduce((n,m)=>n+m.frames,0),audio:report.audio,links:report.links,referenceComparisons:report.referenceComparisons.length}));
