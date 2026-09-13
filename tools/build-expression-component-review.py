"""Read-only artwork audit and component review page; no preset changes."""
from pathlib import Path
from PIL import Image
import json, collections
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'standards/yukkuri-library';OUT=R/'expressions/component-review';OUT.mkdir(parents=True,exist_ok=True)
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
idx=read(R/'expressions/index.json');presets={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in presets.values()}
requested={'O02':'重绘；眼睛参照灵梦旧版小弯月眼，嘴巴参考 N001','N002':'眼睛同 O02 问题；闭口微笑嘴另调','O03':'嘴巴参考八景 U 得意脸','O06':'嘴巴同 N001；小弯月微笑眼同上','N005':'嘴巴同 N001；眼睛重绘（用户写 N0005）','O08':'重绘','N010':'重绘','O09':'嘴巴、眼睛调整','N011':'嘴巴、眼睛调整','N012':'泪光参考含泪笑，贴下眼缘湿润感，不要椭圆','N013':'泪光同 N012','N014':'眼睛、嘴巴重绘','N015':'眼睛、嘴巴重绘'}
usage=collections.defaultdict(set)
for p in presets.values():
 for slot,ref in p['parts'].items():usage[(slot.rsplit('_',1)[0] if slot.endswith(('_left','_right')) else slot,ref)].add(p['review_code'])
families=[
 dict(id='E1',label='小弯月微笑眼',part='eye',examples=['O02','N002','O06','N005'],question='以找回的旧灵梦小弯月眼为基准，确认弧度、宽度和线条粗细。',ref='legacy'),
 dict(id='M1',label='N001 式张嘴微笑',part='mouth',examples=['N001','O02','O06','N005'],question='N001 是你指定的嘴巴基准；确认轮廓、开口大小和内部颜色后，应用到点名的表情。N002 闭口笑另记。',ref='6280'),
 dict(id='E2',label='闭合睡眼／哈欠眼',part='eye',examples=['O08','N010','O09','N011','N012','N013'],question='先判断下弯睡眼与哈欠挤闭眼是否保留为两种部件，再定线条与弧度。',ref='5335'),
 dict(id='E3',label='挤眼含泪的眼睛',part='eye',examples=['N014','N015'],question='确认内眼角汇聚、上下眼皮夹角与粗细；不要把眉毛并入眼睛。',ref='8373'),
 dict(id='T1',label='下眼缘湿润泪光',part='tear',examples=['N012','N013','N008','N009'],question='N008／N009 为含泪笑对照。确认贴下眼缘的范围、亮边与厚度；A/B 帧共用基本形状，只保留小幅水光变化。',ref='7697')]
for fam in families:
 entries=[];union=set()
 for code in fam['examples']:
  p=codes[code];names=[n for n in p['parts'] if n=='face_'+fam['part'] or n.startswith('face_'+fam['part']+'_')];assets=[]
  for n in names:
   ref=p['parts'][n];key=(n.rsplit('_',1)[0] if n.endswith(('_left','_right')) else n,ref);users=sorted(usage[key]);union.update(users)
   assets.append(dict(slot=n,path=ref,size=list(Image.open(R/ref).size),users=users))
  entries.append(dict(code=code,id=p['id'],label=p['label'],assets=assets))
 fam['entries']=entries;fam['existing_users']=sorted(union)
low=[]
for code in requested:
 p=codes[code]
 for n,ref in p['parts'].items():
  if not any(x in n for x in ['eye','mouth','tear']):continue
  if Image.open(R/ref).getbbox() is None:continue
  key=(n.rsplit('_',1)[0] if n.endswith(('_left','_right')) else n,ref)
  if len(usage[key])<=3:low.append(dict(code=code,slot=n,path=ref,users=sorted(usage[key]),status='later-individual-review'))
audit=dict(requests=requested,normalizations={'N0005':'N005'},families=families,low_reuse=low,policy='Review existing parts first; no artwork changed. Exact pixel reuse counts are not authorization to change every dependent expression.',legacy_source='variants/reimu-exercise/reimu.png crop (57,12,66,18); generate.py lines 38-42')
(OUT/'audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
# Extract existing reference pixels only. This is not a newly drawn candidate.
old=Image.open(ROOT/'variants/reimu-exercise/reimu.png').convert('RGBA').crop((57,12,66,18));old.save(OUT/'legacy-small-eye.png')
lines=['# 本轮表情修订登记','','先审高复用部件，不修改正式表情。N0005 按 N005 记录。','']
lines += [f'- {c}：{v}' for c,v in requested.items()]
lines += ['','## 低复用部件，稍后单独判断','','按部件种类与相同像素统计，左右眼不重复计数；最多 3 个表情引用。','']
lines += [f"- {x['code']} / {x['slot']}：共用于 {', '.join(x['users'])}" for x in low]
(OUT/'requests.md').write_text('\n'.join(lines),encoding='utf-8')
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>表情部件核对</title><style>
*{box-sizing:border-box}body{margin:0;background:#eae7de;color:#51463e;font:15px system-ui}main{max-width:1500px;margin:auto;padding:20px}h1{font-size:25px;margin:0}p{line-height:1.6}button,a{color:inherit}button,select{font:inherit;background:#fff7e8;border:1px solid #b8aa91;border-radius:8px;padding:9px;cursor:pointer}button.active{background:#64543f;color:white}.row{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.panel{background:#fff7e8;padding:15px;border-radius:12px;margin:12px 0}.parts{display:grid;grid-template-columns:repeat(auto-fit,minmax(205px,1fr));gap:10px}.part{border:1px solid #cfbea3;border-radius:8px;padding:12px}.pixels{display:flex;gap:16px;min-height:112px;align-items:center;background:#e5d0a7;padding:10px;margin:10px 0;overflow:auto}.pixels img{image-rendering:pixelated}.muted{font-size:12px;color:#796f60;overflow-wrap:anywhere}.refs{display:flex;gap:15px;align-items:center;flex-wrap:wrap}.source{max-width:250px;max-height:170px;background:#e5d0a7;object-fit:contain}.stages{display:grid;grid-template-columns:1fr 1fr;gap:12px}iframe{width:100%;height:555px;border:1px solid #b8aa91;border-radius:12px}.legacy{max-width:560px;width:100%}details{margin:12px 0}@media(max-width:700px){main{padding:10px}.stages{gap:5px}iframe{height:410px}}</style><main><h1>先核对共用部件</h1><p>本页只展示现有素材和参考，没有修改正式表情。选一类，比较放大的部件；点击“在角色上看”检查完整表情。拖动下方角色可同步旋转。</p><div class="row" id="tabs"></div><section class="panel"><h2 id="heading"></h2><p id="question"></p><p class="muted" id="reuse"></p><div class="refs" id="refs"></div><div class="parts" id="parts"></div></section><div class="row"><strong id="current"></strong><button id="front">正面</button><a href="expressions.html">全部表情预览</a><a href="expressions/component-review/requests.md">已登记的修改与低复用清单</a></div><div class="stages"><iframe title="魔理沙" src="assembled/marisa/index.html?compare=1"></iframe><iframe title="灵梦" src="assembled/reimu/index.html?compare=1"></iframe></div><details><summary>已找回的旧灵梦小弯月眼 · 历史预览</summary><p>下排右侧为改后旧版小笑眼。只作为眼睛参考，嘴巴按本轮指定另核对。</p><img class="legacy" src="../../variants/reimu-exercise/face-integration-comparison.png"></details></main><script>const AUDIT=__DATA__;
const get=id=>document.getElementById(id),frames=[...document.querySelectorAll('iframe')];let expression='happy',az=0,el=.04,span=13;
function send(){for(const f of frames)f.contentWindow.postMessage({type:'standard-compare',clip:'idle',time:0,expression,az,el,span,hands:{left:false,right:false}},'*')}
function choose(entry){expression=entry.id;get('current').textContent='完整表情：'+entry.code+' · '+entry.label;send()}
function show(fam){document.querySelectorAll('[data-family]').forEach(b=>b.classList.toggle('active',b.dataset.family===fam.id));get('heading').textContent=fam.id+' · '+fam.label;get('question').textContent=fam.question;get('reuse').textContent='本组现有部件合计被 '+fam.existing_users.length+' 个版本引用（按实际像素统计，尚未决定统一替换范围）：'+fam.existing_users.join('、');get('refs').replaceChildren();const reference=document.createElement('img');reference.className='source';reference.src=fam.ref==='legacy'?'expressions/component-review/legacy-small-eye.png':'../../reference/hakkei-u-expression-audit/sprites/sprite-'+fam.ref+'.png';if(fam.ref==='legacy'){reference.style.width='72px';reference.style.imageRendering='pixelated'}const label=document.createElement('p');label.textContent=fam.ref==='legacy'?'找回的旧灵梦小弯月眼 · 原像素放大':'八景 U 原始参考 · Sprite '+fam.ref+'（仅参考，不加入模型）';get('refs').append(reference,label);get('parts').replaceChildren();for(const e of fam.entries){const card=document.createElement('article');card.className='part';const title=document.createElement('strong');title.textContent=e.code+' · '+e.label;const pixels=document.createElement('div');pixels.className='pixels';for(const a of e.assets){const im=document.createElement('img');im.src=a.path;im.alt=a.slot;im.width=a.size[0]*6;im.height=a.size[1]*6;pixels.append(im)}const users=[...new Set(e.assets.flatMap(a=>a.users))];const note=document.createElement('p');note.className='muted';note.textContent='相同部件用于 '+users.length+' 个版本：'+users.join('、');const b=document.createElement('button');b.textContent='在角色上看 '+e.code;b.onclick=()=>choose(e);card.append(title,pixels,note,b);get('parts').append(card)}choose(fam.entries[0])}
for(const f of AUDIT.families){const b=document.createElement('button');b.textContent=f.id+' '+f.label;b.dataset.family=f.id;b.onclick=()=>show(f);get('tabs').append(b)}get('front').onclick=()=>{az=0;el=.04;span=13;send()};window.addEventListener('message',e=>{if(e.data?.type==='standard-camera'&&frames.some(f=>f.contentWindow===e.source)){az=e.data.az;el=e.data.el;span=e.data.span;send()}});for(const f of frames)f.addEventListener('load',send);show(AUDIT.families[0]);setInterval(send,150);
</script></html>'''
(R/'component-review.html').write_text(page.replace('__DATA__',json.dumps(audit,ensure_ascii=False)),encoding='utf-8')
print([(f['id'],len(f['existing_users'])) for f in families]);print('low-reuse records',len(low))
