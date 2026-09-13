"""Add reversible component substitutions to the component review preview."""
from pathlib import Path
from PIL import Image
import json
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'standards/yukkuri-library'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
audit=read(R/'expressions/component-review/audit.json')
data={}
for f in audit['families']:
 for e in f['entries']:
  key=f['id']+'/'+e['code'];patches={}
  for a in e['assets']:
   im=Image.open(R/a['path']).convert('RGBA');patches[a['slot']]=dict(size=list(im.size),pixels=list(im.tobytes()))
  data[key]=dict(label=e['code']+' 的'+f['label'],parts=patches)
im=Image.open(R/'expressions/component-review/legacy-small-eye.png').convert('RGBA')
im.putdata([(*p[:3],0 if p[:3]==(255,231,185) else p[3]) for p in im.getdata()])
tile=Image.new('RGBA',(15,12));tile.paste(im,(3,4))
data['legacy']=dict(label='旧灵梦小弯月微笑眼',parts={n:dict(size=[15,12],pixels=list(tile.tobytes())) for n in ['face_eye_left','face_eye_right']})
bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']}
p=R/'component-review.html';s=p.read_text(encoding='utf-8')
s=s.replace('<div class="stages">','<div class="panel"><div class="row"><button id="tryLegacy">试用旧版小弯月微笑眼</button><button id="compareOriginal">按住查看替换前</button><button id="clearTrial">恢复当前表情原部件</button></div><strong id="trialStatus">尚未替换部件</strong><p class="muted">可分别替换眼睛、嘴巴和眼泪，再切换完整表情查看效果。这里只临时试用，不写入正式素材。</p></div><div class="stages">')
s=s.replace("card.append(title,pixels,note,b);", "const use=document.createElement('button');use.textContent='试用此部件';use.onclick=()=>applyTrial(fam.id+'/'+e.code);card.append(title,pixels,note,b,use);")
extra='''
const TRIALS=__TRIALS__,BINDINGS=__BINDINGS__;let trialParts={},trialLabels={},peek=false;
function sendTrials(){frames.forEach((f,i)=>{const uv=BINDINGS[i===0?'marisa':'reimu'];f.contentWindow.postMessage({type:'standard-part-trial',parts:peek?[]:Object.entries(trialParts).map(([slot,p])=>({...p,uv:uv[slot]}))},'*')})}
function applyTrial(key){const t=TRIALS[key];for(const [slot,part] of Object.entries(t.parts)){trialParts[slot]=part;trialLabels[slot]=t.label}get('trialStatus').textContent='试用中：'+[...new Set(Object.values(trialLabels))].join(' ＋ ');sendTrials()}
get('tryLegacy').onclick=()=>applyTrial('legacy');get('clearTrial').onclick=()=>{trialParts={};trialLabels={};get('trialStatus').textContent='已恢复当前表情原部件';sendTrials()};
function stopPeek(){peek=false;sendTrials()}
get('compareOriginal').onpointerdown=e=>{e.currentTarget.setPointerCapture(e.pointerId);peek=true;sendTrials()};get('compareOriginal').onpointerup=stopPeek;get('compareOriginal').onpointercancel=stopPeek;get('compareOriginal').onlostpointercapture=stopPeek;
get('compareOriginal').onkeydown=e=>{if(e.key===' '||e.key==='Enter'){e.preventDefault();peek=true;sendTrials()}};get('compareOriginal').onkeyup=stopPeek;window.addEventListener('blur',stopPeek);for(const f of frames)f.addEventListener('load',sendTrials);
'''.replace('__TRIALS__',json.dumps(data,ensure_ascii=False)).replace('__BINDINGS__',json.dumps(bindings))
s=s.replace('</script>',extra+'</script>');p.write_text(s,encoding='utf-8')
runtime='''
let trialParts=[],trialCache={};
window.addEventListener('message',e=>{if(e.source!==parent||e.data?.type!=='standard-part-trial')return;trialParts=e.data.parts||[];trialCache={};dirty=true});
function applyPartTrial(base,preset){if(!trialParts.length)return base;if(trialCache[preset])return trialCache[preset];const out=new Uint8ClampedArray(base),size=DATA.textureSize||128;for(const p of trialParts){const [u,v,U,V]=p.uv,[w,h]=p.size;if(w!==U-u||h!==V-v)continue;for(let y=0;y<h;y++)for(let x=0;x<w;x++){const src=(y*w+x)*4,dst=((v+y)*size+u+x)*4;for(let c=0;c<4;c++)out[dst+c]=p.pixels[src+c]}}return trialCache[preset]=out}
'''
for p in [R/'runtime/viewer.html',R/'assembled/marisa/index.html',R/'assembled/reimu/index.html']:
 s=p.read_text(encoding='utf-8')
 if 'function applyPartTrial' not in s:
  s=s.replace('function layerTexture(preset){',runtime+'\nfunction layerTexture(preset){')
  s=s.replace('if(effect===\'none\'||!DATA.effects[effect])return base;','if(effect===\'none\'||!DATA.effects[effect])return applyPartTrial(base,preset);')
  s=s.replace('resolvedExpression:selectedExpression(),','trialPartCount:trialParts.length,resolvedExpression:selectedExpression(),')
  p.write_text(s,encoding='utf-8')
print('Enabled temporary eye, mouth, tear replacement and reset')
