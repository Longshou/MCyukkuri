"""Editable pixel-grid candidates rendered by the review page; originals stay untouched."""
from pathlib import Path
from PIL import Image
import json
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'standards/yukkuri-library';OUT=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
idx=read(R/'expressions/index.json');presets={k:read(R/v) for k,v in idx['presets'].items()}
palette={'.':[0,0,0,0],'x':[72,53,46,255],'b':[123,76,64,255],'r':[160,80,68,255],'t':[211,127,115,255],'h':[248,251,238,225],'w':[190,217,220,190],'s':[135,181,194,120]}
def grid(size,rows,y=0,x=0):
 full=[list('.'*size[0]) for _ in range(size[1])]
 for j,row in enumerate(rows):
  for i,c in enumerate(row):
   assert x+i<size[0] and y+j<size[1];full[y+j][x+i]=c
 return dict(size=size,rows=[''.join(row) for row in full])
def pair(part,g):return {'face_'+part+'_left':g,'face_'+part+'_right':dict(g,mirror=True)}
candidates=[]
def add(id,label,family,parts,note):candidates.append(dict(id=id,label=label,family=family,parts=parts,note=note,status='candidate'))
add('E1-A','小弧线笑眼','E1',pair('eye',grid([15,12],['...xxx...','.xx...xx.','x.......x'],5,3)),'单像素细线，9 像素宽；我的首选，减少尖角与厚重感。')
add('E1-B','细弯月笑眼','E1',pair('eye',grid([15,12],['...xxx...','.xx...xx.','x..bbb..x','.bb...bb.'],4,3)),'保留细月牙空隙，下缘稍浅，避免上下两道同样粗。')
add('E1-C','舒展笑眯眼','E1',pair('eye',grid([15,12],['....xxx....','..xx...xx..','.x.......x.','x.........x'],4,2)),'比 A 宽两像素，适合张嘴笑；仍保持单线。')
add('E2-A','放松闭眼','E2',pair('eye',grid([15,12],['x.......x','.xx...xx.','...xxx...'],5,3)),'细而浅的下弧，适合睡眠与含泪闭眼。')
add('E2-B','熟睡闭眼','E2',pair('eye',grid([15,12],['x.........x','.xx.....xx.','...xxxxx...','....bbb....'],5,2)),'下缘略厚，保留柔和眼皮重量，不用椭圆眼框。')
add('M2-A','放松流口水嘴','M2',{'face_mouth':grid([14,12],['.xxxxxx.','xrrrrrrx','xrttttrx','.xttttx.','..xxxx..'],4,3)},'扁圆开口、细边框和较小舌面；沿用当前表情独立的口水层。')
add('M3-A','挤眼小噘嘴','M3',{'face_mouth':grid([14,12],['..x..','.x.x.','x...x','.....','.b.b.','.b.b.'],3,5)},'收紧的小尖口，下面只留两条短皱褶；与噗咕闭眼线组合查看。')
add('T1-A','睁眼下缘泪光','T1',pair('tear',grid([15,16],['..hhhhhhh..','.hwwwwwwwh.','..sswwwss..'],4,2)),'薄亮边包住下眼缘，半透明水色，没有独立椭圆水珠。')
add('T1-B','闭眼下缘泪光','T1',pair('tear',grid([15,16],['.hhhhhhh.','hwwwwwwwh','.sswwwss.'],1,3)),'上移到闭合眼皮下方，适合 N012／N013；与睁眼款分开比较。')
# Existing reference parts are reused byte-for-byte, without geometry or expression side effects.
def existing(id,slots):
 result={}
 for slot in slots:
  im=Image.open(R/presets[id]['parts'][slot]).convert('RGBA');result[slot]=dict(size=list(im.size),pixels=list(im.tobytes()))
 return result
add('M1-N001','N001 原有嘴巴','M1',existing('open_smile',['face_mouth']),'直接复用 N001 嘴巴，只替换嘴，不换眼睛。')
add('E3-PUFF','噗咕闭眼线','E3',existing('puff',['face_eye_left','face_eye_right']),'直接复用噗咕眼线；只换像素，不启用鼓脸或移动眼睛。')
families=[dict(id='E1',label='微笑眼 · 3 款',base='happy',default='E1-A'),dict(id='M1',label='试用 N001 嘴巴',base='happy',default='M1-N001'),dict(id='M2',label='流口水嘴 · 新稿',base='drool',default='M2-A'),dict(id='E2',label='闭合眼 · 2 款',base='sleep',default='E2-A'),dict(id='E3',label='试用噗咕眼线',base='squeezed_teary',default='E3-PUFF'),dict(id='M3',label='挤眼嘴 · 新稿',base='squeezed_teary',default='M3-A'),dict(id='T1',label='湿润泪光 · 2 款',base='tear_sleep',default='T1-B')]
data=dict(palette=palette,candidates=candidates,families=families,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']},presets={id:dict(id=id,label=p['label'],code=p['review_code'],parts={n:existing(id,[n])[n] for n in p['parts']}) for id,p in presets.items()})
(OUT/'redraw-candidates.json').write_text(json.dumps(dict(palette=palette,candidates=candidates,families=families),ensure_ascii=False,indent=2),encoding='utf-8')
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>表情重绘 · 新老试用对比</title><style>
*{box-sizing:border-box}body{margin:0;background:#eae7de;color:#51463e;font:15px system-ui}main{max-width:1500px;margin:auto;padding:18px}h1{font-size:25px;margin:0}p{line-height:1.5}button,select{font:inherit;padding:9px 12px;border:1px solid #b8aa91;border-radius:8px;background:#fff7e8;color:inherit;cursor:pointer}button.active{background:#65543e;color:white}.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:12px 0}.panel{background:#fff7e8;border-radius:10px;padding:14px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px}.card{padding:12px;border:1px solid #ccbda4;border-radius:8px}.card.active{border:2px solid #8b7650;padding:11px}.pair{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:8px 0}.tile{background:#e5d0a7;text-align:center;padding:8px;overflow:auto}.tile canvas{image-rendering:pixelated;max-width:100%;object-fit:contain}.note{font-size:12px;color:#7e705e}.stages{display:grid;grid-template-columns:1fr 1fr;gap:12px}iframe{width:100%;height:550px;border:1px solid #b8aa91;border-radius:12px}#state{padding:10px;background:#ded4bf;border-radius:8px;flex:1}a{color:inherit}@media(max-width:650px){main{padding:10px}.stages{gap:5px}iframe{height:415px}}
</style><main><h1>表情重绘 · 新老试用对比</h1><p class="note">9 个新稿＋2 个现有部件快捷试用。选择候选后，两角色同步更新。正式表情未被覆盖。</p><div class="row" id="tabs"></div><div class="row"><label>完整表情 <select id="expression"></select></label><button id="quickMouth">试用 N001 嘴巴</button><button id="quickPuff">试用噗咕闭眼线</button><button id="reset">清除全部试用</button></div><div class="panel"><p id="description"></p><div id="cards" class="cards"></div></div><div class="row"><button id="old">看旧版</button><button id="new">看试用版</button><button id="front">正面</button><button id="angle">斜侧面</button><span id="state"></span></div><div class="stages"><iframe title="魔理沙表情对比" src="assembled/marisa/index.html?compare=1"></iframe><iframe title="灵梦表情对比" src="assembled/reimu/index.html?compare=1"></iframe></div><p class="note">可以按 E1-A、E2-B、M2-A 等编号反馈。候选可跨类别组合；切到下一类别会清除试用并打开对应完整表情，方便从旧版重新比较。<a href="expressions/component-review/requests.md">本轮修改清单</a></p></main><script>const DATA=__DATA__;
const get=id=>document.getElementById(id),frames=[...document.querySelectorAll('iframe')],candidates=Object.fromEntries(DATA.candidates.map(c=>[c.id,c]));let family='E1',expression='happy',trial={},labels={},isOld=false,az=0,el=.04,span=13;
function decode(p){if(p.pixels)return p;const [w,h]=p.size,pixels=[];for(let y=0;y<h;y++)for(let x=0;x<w;x++)pixels.push(...DATA.palette[p.rows[y][p.mirror?w-1-x:x]]);return {size:p.size,pixels}}
function send(){for(const f of frames)f.contentWindow.postMessage({type:'standard-compare',clip:'idle',time:0,expression,az,el,span,effect:'none',hands:{left:false,right:false}},'*')}
function patch(){frames.forEach((f,i)=>f.contentWindow.postMessage({type:'standard-part-trial',parts:isOld?[]:Object.entries(trial).map(([n,p])=>({...p,uv:DATA.bindings[i===0?'marisa':'reimu'][n]}))},'*'));get('old').classList.toggle('active',isOld);get('new').classList.toggle('active',!isOld);get('state').textContent=(isOld?'旧版：':'试用版：')+DATA.presets[expression].code+' · '+(isOld?'当前表情原部件':([...new Set(Object.values(labels))].join(' ＋ ')||'尚未替换'));}
function apply(id){const c=candidates[id];for(const [n,p] of Object.entries(c.parts)){trial[n]=decode(p);labels[n]=id}isOld=false;renderCards();patch()}
function tiles(parts,names){const [w,h]=parts[names[0]].size,canvas=document.createElement('canvas');canvas.width=w*names.length+4*(names.length-1);canvas.height=h;const ctx=canvas.getContext('2d');names.forEach((n,i)=>{const p=decode(parts[n]);ctx.putImageData(new ImageData(new Uint8ClampedArray(p.pixels),...p.size),i*(w+4),0)});canvas.style.width=canvas.width*4+'px';canvas.style.height=h*4+'px';return canvas}
function renderCards(){get('cards').replaceChildren();for(const c of DATA.candidates.filter(c=>c.family===family)){const card=document.createElement('article');card.className='card'+(Object.values(labels).includes(c.id)?' active':'');const title=document.createElement('strong');title.textContent=c.id+' · '+c.label;const pair=document.createElement('div');pair.className='pair';const names=Object.keys(c.parts);for(const [label,parts] of [['当前旧版',DATA.presets[expression].parts],['候选 / 参考',c.parts]]){const tile=document.createElement('div');tile.className='tile';const caption=document.createElement('div');caption.textContent=label;tile.append(caption,tiles(parts,names));pair.append(tile)}const note=document.createElement('p');note.className='note';note.textContent=c.note;const b=document.createElement('button');b.textContent='试用 '+c.id;b.onclick=()=>apply(c.id);card.append(title,pair,note,b);get('cards').append(card)}}
function selectFamily(id){family=id;const f=DATA.families.find(f=>f.id===id);expression=f.base;trial={};labels={};get('expression').value=expression;get('description').textContent=f.label+'：左侧为当前完整表情的旧部件，右侧为候选。';document.querySelectorAll('[data-family]').forEach(b=>b.classList.toggle('active',b.dataset.family===id));send();apply(f.default)}
for(const f of DATA.families){const b=document.createElement('button');b.textContent=f.label;b.dataset.family=f.id;b.onclick=()=>selectFamily(f.id);get('tabs').append(b)}for(const p of Object.values(DATA.presets)){const o=document.createElement('option');o.value=p.id;o.textContent=p.code+' · '+p.label;get('expression').append(o)}get('expression').onchange=e=>{expression=e.target.value;send();renderCards();patch()};get('quickMouth').onclick=()=>apply('M1-N001');get('quickPuff').onclick=()=>apply('E3-PUFF');get('reset').onclick=()=>{trial={};labels={};isOld=false;renderCards();patch()};get('old').onclick=()=>{isOld=true;patch()};get('new').onclick=()=>{isOld=false;patch()};get('front').onclick=()=>{az=0;el=.04;send()};get('angle').onclick=()=>{az=-.45;el=.14;send()};window.addEventListener('message',e=>{if(e.data?.type==='standard-camera'&&frames.some(f=>f.contentWindow===e.source)){az=e.data.az;el=e.data.el;span=e.data.span;send()}});for(const f of frames)f.addEventListener('load',()=>{send();patch()});selectFamily('E1');setInterval(send,150);
</script></html>'''
page=page.replace('<button id="reset">','<label><input type="checkbox" id="keep">切换分类时保留试用</label><button id="reset">')
page=page.replace("expression=f.base;trial={};labels={};", "if(!get('keep').checked){expression=f.base;trial={};labels={};}")
page=page.replace('候选可跨类别组合；切到下一类别会清除试用并打开对应完整表情，方便从旧版重新比较。','勾选“切换分类时保留试用”可组合眼睛、嘴巴和泪光；不勾选时切换分类会从对应旧版重新比较。')
(R/'component-review.html').write_text(page.replace('__DATA__',json.dumps(data,ensure_ascii=False,separators=(',',':'))),encoding='utf-8')
print('Created',len(candidates),'candidates in',len(families),'groups; no original presets changed')
