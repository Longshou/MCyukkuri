from pathlib import Path
from PIL import Image
import json
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';O=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
idx=read(R/'expressions/index.json');ps={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in ps.values()};before=read(O/'before-fine-mouth.json')
def px(p):return {n:dict(size=list((im:=Image.open(R/ref).convert('RGBA')).size),pixels=list(im.tobytes())) for n,ref in p['parts'].items()}
bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']}
page=(R/'component-review.html').read_text(encoding='utf-8');a=page.index('const DATA=')+len('const DATA=');b=page.index(';\nconst get=',a);data=json.loads(page[a:b]);rows=[]
notes={'N014':'嘴部图块提升为 28×24，用更多、更细的像素描线。','N015':'与 N014 共用细像素尖嘴。','O03':'V 形采用细像素线，并在上轮基础上再放大 50%。','N012':'含泪睡眠嘴巴放大 30%，保留已接受的薄泪光。','N013':'与 N012 共用放大的嘴。','N010':'单独修复：缩小并下移腮红，薄泪光上移，重新绘制张嘴哈欠。'}
for c in notes:
 p=codes[c];q=before[p['id']];oldscale=q.get('geometry_offsets',{}).get('face_mouth',{}).get('scale',[1,1,1])[0];newscale=p.get('geometry_offsets',{}).get('face_mouth',{}).get('scale',[1,1,1])[0];parts=px(p)
 rows.append(dict(code=c,id=p['id'],label=p['label'],before=px(q),after=parts,variants=[dict(label='本轮修正',parts=parts,description=notes[c])],change=notes[c],note='已写入素材。哈欠动画见上方入口。',before_scale=oldscale/newscale))
data.update(rows=rows,bindings=bindings)
page=page[:a]+json.dumps(data,ensure_ascii=False,separators=(',',':'))+page[b:]
page=page.replace('<h1>按表情看重绘前后</h1>','<h1>按表情看重绘前后</h1><p><a href="yawn-animation.html">▶ 播放完整哈欠表情动画</a></p>')
page=page.replace('</style>','.pixelrow{height:116px}</style>')
(R/'component-review.html').write_text(page,encoding='utf-8')
anim=read(O/'yawn-animation-source.json');anim['bindings']=bindings
anim['mouthIndices']={n:next(i for i,e in enumerate(read(R/f'assembled/{n}/{n}.bbmodel')['elements']) if e['name']=='face_mouth') for n in ['marisa','reimu']}
html='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>打哈欠 · 表情动画</title><style>*{box-sizing:border-box}body{margin:0;background:#eae7de;color:#51463e;font:16px system-ui}main{max-width:1500px;margin:auto;padding:22px}h1{font-size:26px}button,input{font:inherit}button{padding:10px 16px;border:1px solid #b8aa91;border-radius:8px;background:#fff7e8;color:inherit;cursor:pointer}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}#time{flex:1;min-width:180px}#phase{background:#fff7e8;padding:12px;border-radius:8px;min-width:200px}.stages{display:grid;grid-template-columns:1fr 1fr;gap:14px}iframe{width:100%;height:570px;border:1px solid #b8aa91;border-radius:12px}.note{font-size:13px;line-height:1.7;color:#796f60}a{color:inherit}@media(max-width:650px){main{padding:10px}.stages{gap:5px}}</style><main><h1>打哈欠 · 表情动画</h1><p class="note">放松 → 闭眼吸气 → 张嘴 → 保持 → 收口 → 恢复。两角色同步播放，可以拖动旋转、滚轮缩放。</p><div class="row"><button id="play">暂停</button><button id="restart">从头播放</button><input id="time" type="range" min="0" max="5.8" step=".01" value="0"><span id="clock"></span><span id="phase"></span><button id="front">正面</button></div><div class="stages"><iframe title="魔理沙哈欠" src="assembled/marisa/index.html?compare=1"></iframe><iframe title="灵梦哈欠" src="assembled/reimu/index.html?compare=1"></iframe></div><p><a href="component-review.html">查看嘴型与 N010 修复前后</a></p><p class="note">N010 的腮红缩小并下移，泪光向眼缘收拢。当前演示为可循环的表情序列，默认每轮 5.8 秒。</p></main><script>const DATA=__DATA__;
const get=id=>document.getElementById(id),frames=[...document.querySelectorAll('iframe')];let time=0,playing=true,last=0,az=0,el=.04,span=13;
function send(){const f=DATA.frames.findLast(f=>time>=f.time)||DATA.frames[0];get('phase').textContent=f.label;get('clock').textContent=time.toFixed(2)+' / '+DATA.duration.toFixed(1)+' 秒';get('time').value=time;get('play').textContent=playing?'暂停':'播放';frames.forEach((win,i)=>{const name=i===0?'marisa':'reimu';win.contentWindow.postMessage({type:'standard-compare',clip:'idle',time:0,expression:DATA.expression,az,el,span,effect:'none',hands:{left:false,right:false}},'*');const stretch=time>1.25&&time<3.45?1+.05*Math.sin((time-1.25)/2.2*Math.PI):1;win.contentWindow.postMessage({type:'standard-part-trial',scales:[{index:DATA.mouthIndices[name],scale:[1,stretch,1]}],parts:Object.entries(f.parts).map(([slot,p])=>({...p,uv:DATA.bindings[name][slot]}))},'*')})}
get('play').onclick=()=>{playing=!playing;last=0;send()};get('restart').onclick=()=>{time=0;playing=true;last=0;send()};get('time').oninput=e=>{time=+e.target.value;playing=false;send()};get('front').onclick=()=>{az=0;el=.04;span=13;send()};window.addEventListener('message',e=>{if(e.data?.type==='standard-camera'&&frames.some(f=>f.contentWindow===e.source)){az=e.data.az;el=e.data.el;span=e.data.span;send()}});function tick(now){if(last&&playing)time=(time+(now-last)/1000)%DATA.duration;last=now;send();requestAnimationFrame(tick)}for(const f of frames)f.addEventListener('load',send);document.addEventListener('visibilitychange',()=>last=0);requestAnimationFrame(tick);
</script></html>'''
(R/'yawn-animation.html').write_text(html.replace('__DATA__',json.dumps(anim,ensure_ascii=False,separators=(',',':'))),encoding='utf-8')
print('Published updated before/after page and complete yawn animation')
