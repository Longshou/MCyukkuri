"""Show only unresolved redraws, one expression at a time, before versus after."""
from pathlib import Path
import json,copy
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';OUT=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
old=read(OUT/'redraw-candidates.json');original={c['id']:c for c in old['candidates']};new=copy.deepcopy(original)
def grid(size,rows,x,y):
 a=[list('.'*size[0]) for _ in range(size[1])]
 for j,row in enumerate(rows):
  for i,c in enumerate(row):a[y+j][x+i]=c
 return dict(size=size,rows=[''.join(r) for r in a])
def eyes(g):return {'face_eye_left':g,'face_eye_right':dict(g,mirror=True)}
new['E1-B']['parts']=eyes(grid([15,12],['...xxx...','.xxxxxxx.','xx.....xx'],3,5))
new['E1-B']['note']='取消上下分离的椭圆眼框，改成两端收尖的实心小月牙。'
new['E2-B']['parts']=eyes(grid([15,12],['x.......x','.xx...xx.','...xxx...'],3,6))
new['E2-B']['note']='去掉原来的厚下缘和浅色重影，缩窄为平缓、细而干净的闭眼线。'
new['M2-A']['parts']={'face_mouth':grid([14,12],['x......x','xxxxxxxx','xrrrrrrx','.xrtttrx','.xttttx.','..xxxx..'],3,3)}
new['M2-A']['note']='从小椭圆改为上沿放松的 U 形开口，保留嘴角，舌面集中在下半部；口水层不变。'
new['M3-A']['parts']={'face_mouth':grid([14,12],['x...x...x','.x.x.x.x.','..x...x..','....b....','....b....'],3,4)}
new['M3-A']['note']='去掉鼻子般的尖三角，改成收紧的 W 形双唇和一条短褶线。'
accepted=['E1-A','E1-C','E2-A','M1-N001','E3-PUFF'];redraw=['E1-B','M2-A','E2-B','M3-A']
decisions=dict(accepted=accepted,redraw=redraw,deferred=['T1-A','T1-B'],accepted_note='Accepted by exclusion from the user redraw/deferred list; removed from candidate selection. Accepted parts are held identical on both sides.',scope='Preview recipes only; no global preset replacement.')
index=read(R/'expressions/index.json');presets={k:read(R/v) for k,v in index['presets'].items()};bycode={p['review_code']:p for p in presets.values()}
specs=[('N002','E1-B',[],'眼睛改为小月牙；闭口微笑嘴仍登记为后续单独审。'),('O06','M2-A',['E1-A'],'已接受的细笑眼在两边一致，只比较流口水嘴。'),('N005','M2-A',['E1-A'],'眼睛采用已接受的细笑眼；口水滴落帧保留，只比较嘴。'),('O09','E2-B',[],'本次只比较熟睡闭眼；睡眠嘴巴仍在单独待审清单。'),('N011','E2-B',[],'与 O09 共用闭眼线；保留本表情的口水滴落帧。'),('N014','M3-A',['E3-PUFF'],'两边都用已接受的噗咕眼线，只比较挤眼嘴；泪光暂不动。'),('N015','M3-A',['E3-PUFF'],'与 N014 共用挤眼嘴；保留本表情的泪帧，泪光暂不动。')]
def combine(ids,source):
 parts={}
 for id in ids:parts.update(source[id]['parts'])
 return parts
rows=[]
for code,cid,common,note in specs:
 p=bycode[code];rows.append(dict(code=code,id=p['id'],label=p['label'],candidate=cid,accepted=common,note=note,change=new[cid]['note'],before=combine(common+[cid],original),after=combine(common+[cid],new)))
deferred=dict(tears=['N012','N013'],individual=['O03 得意嘴','N002 闭口微笑嘴','O08 / N010 哈欠表情','O09 / N011 睡眠嘴'],completed_component_review=accepted)
data=dict(palette=old['palette'],rows=rows,decisions=decisions,deferred=deferred,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']})
(OUT/'review-decisions.json').write_text(json.dumps(decisions,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'redraw-round-2.json').write_text(json.dumps(dict(candidates=[new[k] for k in redraw],comparison=data),ensure_ascii=False,indent=2),encoding='utf-8')
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>逐表情 · 重绘前后对比</title><style>
*{box-sizing:border-box}body{margin:0;background:#eae7de;color:#51463e;font:15px system-ui}main{max-width:1550px;margin:auto;padding:18px}h1{font-size:25px;margin:0}p{line-height:1.6;margin:8px 0}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:14px 0}button,select{font:inherit;background:#fff7e8;border:1px solid #b8aa91;border-radius:8px;padding:9px 13px;color:inherit;cursor:pointer}.note{font-size:13px;color:#796f60}.panel{background:#fff7e8;padding:12px 16px;border-radius:10px}h2{font-size:20px;margin:0 0 8px}.comparisons{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:14px}.col{min-width:0}.label{font-size:18px;font-weight:bold;padding:12px;background:#dcd1bd;border-radius:10px 10px 0 0}.after .label{background:#c9d6bb}iframe{width:100%;height:420px;display:block;border:1px solid #b8aa91;background:#eae7de}.pixelrow{display:flex;align-items:center;gap:12px;min-height:75px;padding:10px;background:#e5d0a7}.pixelrow canvas{image-rendering:pixelated}details{margin:18px 0}.tags span{display:inline-block;margin:3px;padding:5px 8px;background:#dfd8c9;border-radius:5px}@media(max-width:700px){main{padding:10px}.comparisons{gap:7px}iframe{height:345px}.label{font-size:15px}}
</style><main><h1>按表情看重绘前后</h1><p class="note">只列本轮仍需重绘的 7 张表情。已接受的部件在两边保持一致；泪光暂不修改。</p><div class="row"><button id="prev">上一张</button><select id="expression" aria-label="选择表情"></select><button id="next">下一张</button><span id="count"></span><button id="front">正面</button><button id="angle">斜侧面</button></div><section class="panel"><h2 id="title"></h2><p id="change"></p><p class="note" id="note"></p></section><div class="comparisons"><section class="col"><div class="label">重绘前 · 上次候选</div><div class="pixelrow" id="beforeParts"></div><iframe title="重绘前魔理沙" src="assembled/marisa/index.html?compare=1"></iframe><iframe title="重绘前灵梦" src="assembled/reimu/index.html?compare=1"></iframe></section><section class="col after"><div class="label">重绘后 · 本次新稿</div><div class="pixelrow" id="afterParts"></div><iframe title="重绘后魔理沙" src="assembled/marisa/index.html?compare=1"></iframe><iframe title="重绘后灵梦" src="assembled/reimu/index.html?compare=1"></iframe></section></div><details><summary>已接受及稍后再看的记录</summary><p>已接受并移出待选：E1-A、E1-C、E2-A、N001 嘴巴、噗咕闭眼线。这里只在对照中复用，尚未批量覆盖正式表情。</p><p>泪光 T1-A / T1-B 保持待定，N012 / N013 稍后再看。</p><p>低复用项目仍登记：O03 得意嘴、N002 闭口微笑嘴、O08 / N010 哈欠表情、O09 / N011 睡眠嘴。</p></details></main><script>const DATA=__DATA__;
const get=id=>document.getElementById(id),frames=[...document.querySelectorAll('iframe')];let selected=0,az=0,el=.04,span=13;
function decode(p){if(p.pixels)return p;const [w,h]=p.size,pixels=[];for(let y=0;y<h;y++)for(let x=0;x<w;x++)pixels.push(...DATA.palette[p.rows[y][p.mirror?w-1-x:x]]);return {size:p.size,pixels}}
function send(){const row=DATA.rows[selected];frames.forEach((f,i)=>{const name=i%2===0?'marisa':'reimu',parts=i<2?row.before:row.after;f.contentWindow.postMessage({type:'standard-compare',clip:'idle',time:0,expression:row.id,az,el,span,effect:'none',hands:{left:false,right:false}},'*');f.contentWindow.postMessage({type:'standard-part-trial',parts:Object.entries(parts).map(([slot,p])=>({...decode(p),uv:DATA.bindings[name][slot]}))},'*')})}
function pixels(target,parts,slots){get(target).replaceChildren();for(const slot of slots){const p=decode(parts[slot]),canvas=document.createElement('canvas');[canvas.width,canvas.height]=p.size;canvas.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(p.pixels),...p.size),0,0);canvas.style.width=canvas.width*4+'px';canvas.style.height=canvas.height*4+'px';get(target).append(canvas)}}
function show(){const r=DATA.rows[selected];get('expression').value=String(selected);get('count').textContent=(selected+1)+' / '+DATA.rows.length;get('title').textContent=r.code+' · '+r.label;get('change').textContent=r.change;get('note').textContent=r.note;const changed=Object.keys(r.after).filter(n=>JSON.stringify(r.before[n])!==JSON.stringify(r.after[n]));pixels('beforeParts',r.before,changed);pixels('afterParts',r.after,changed);send()}
DATA.rows.forEach((r,i)=>{const o=document.createElement('option');o.value=i;o.textContent=r.code+' · '+r.label;get('expression').append(o)});get('expression').onchange=e=>{selected=+e.target.value;show()};get('prev').onclick=()=>{selected=(selected+DATA.rows.length-1)%DATA.rows.length;show()};get('next').onclick=()=>{selected=(selected+1)%DATA.rows.length;show()};get('front').onclick=()=>{az=0;el=.04;send()};get('angle').onclick=()=>{az=-.45;el=.14;send()};window.addEventListener('message',e=>{if(e.data?.type==='standard-camera'&&frames.some(f=>f.contentWindow===e.source)){az=e.data.az;el=e.data.el;span=e.data.span;send()}});for(const f of frames)f.addEventListener('load',send);show();setInterval(send,250);
</script></html>'''
page=page.replace('index.html?compare=1','index.html?compare=1&compact=1')
(R/'component-review.html').write_text(page.replace('__DATA__',json.dumps(data,ensure_ascii=False,separators=(',',':'))),encoding='utf-8')
for path in [R/'runtime/viewer.html',R/'assembled/marisa/index.html',R/'assembled/reimu/index.html']:
 s=path.read_text(encoding='utf-8')
 if 'compactStyle' not in s:
  marker="const embedded=new URLSearchParams(location.search).has('compare');"
  addition="if(new URLSearchParams(location.search).has('compact')){const compactStyle=document.createElement('style');compactStyle.textContent='.compare canvas{height:310px}.compare main{padding:10px}';document.head.append(compactStyle);}"
  assert marker in s
  path.write_text(s.replace(marker,marker+addition),encoding='utf-8')
print('7 expression comparisons; 4 revised parts; accepted and tears removed from pending choices')
