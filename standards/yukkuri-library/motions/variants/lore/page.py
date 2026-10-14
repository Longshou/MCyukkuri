"""Write a self-contained interactive page that plays the world-setting clips on the game models.

Embeds, per model, the runtime triangles (int16), two-bone weights, bones with their idle
pose, the lore clips plus hop / shell_retract from the motion corrections, and the expression
textures the game shows for each behavior; draws with the library's runtime/render.js. The
page opens from disk without a server. Writing it does not start a browser or the game.

  python page.py [--out art/source/motion-corrections/previews/current/lore/motions.html]
"""
from pathlib import Path
import argparse, base64, datetime, json, struct, sys
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import preview as P

LIB = HERE.parents[2]
EXTRA = {'hop': ('跳动', '玩具反应'), 'shell_retract': ('缩壳', '螺壳魔理沙受惊、受伤（仅螺壳）')}
LABELS = {'marisa': '魔理沙', 'reimu': '灵梦', 'house_marisa': '家油魔理沙', 'marisa_bean': '豆形魔理沙',
          'remilia': '蕾米莉亚', 'marisa_tsumuri': '螺壳魔理沙'}


def b64(fmt, values): return base64.b64encode(struct.pack(f'<{len(values)}{fmt}', *values)).decode()


def panel(ident, corrections, clip_names):
    p = P.Posed(ident, corrections); m = p.model
    # Drop triangles that idle hides (zero-scale puff and contact parts) to keep the page small.
    rest = p.frame('idle', 0)[0]
    area = np.linalg.norm(np.cross(rest[:, 1]-rest[:, 0], rest[:, 2]-rest[:, 0]), axis=1)
    keep = np.repeat(area > 1e-7, 3)
    v = np.c_[p.v, p.uv, p.n][keep]
    packed = np.c_[np.round(v[:, :3]*1024), np.round(v[:, 3:5]*16384), np.round(v[:, 5:8]*1024)].clip(-32768, 32767).astype(int).ravel().tolist()
    weights = np.c_[p.a[keep], p.b[keep]].ravel().tolist(); blend = np.round(p.q[keep, 0]*255).astype(int).tolist()
    base = {}
    for tr in p.clips['idle']['tracks']:
        base.setdefault(tr['bone'], {})[tr['channel']] = P.sample(tr['keys'], 0)
    bones = []
    for i, b in enumerate(m['bones']):
        q = base.get(i, {})
        bones.append([b['parent']]+[round(x, 4) for x in b['pivot']+q.get('position', [0, 0, 0])+q.get('rotation', [0, 0, 0])+q.get('scale', [1, 1, 1])])
    clips = {}
    for name in clip_names:
        if name not in p.clips: continue
        c = p.clips[name]
        clips[name] = dict(d=c['duration'], loop=c['loop'], tracks=[[tr['bone'], {'position': 0, 'rotation': 3, 'scale': 6}[tr['channel']],
                                                                      [round(x, 4) for k in tr['keys'] for x in k]] for tr in c['tracks']])
    textures = {}
    for face in sorted(set(P.FACE.values())):
        path = P.ASSETS/'textures/entity'/ident/f'{face}.png'
        if path.exists(): textures[face] = 'data:image/png;base64,'+base64.b64encode(path.read_bytes()).decode()
    size = struct.unpack('>I', (P.ASSETS/'textures/entity'/ident/'neutral.png').read_bytes()[16:20])[0]
    return dict(id=ident, label=LABELS.get(ident, ident), vertices=b64('h', packed), weights=b64('H', weights), blend=b64('B', blend),
                bones=bones, clips=clips, textures=textures, textureSize=size, triangles=int(keep.sum()//3))


PAGE = r'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>世界观动作 · 交互预览</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#eae7de;color:#51463e;font:15px/1.6 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
main{max-width:1280px;margin:auto;padding:18px}h1{font-size:22px;margin:0 0 4px}p{margin:4px 0 8px}
.bar{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin:6px 0}.sticky{position:sticky;top:0;background:#eae7de;padding:6px 0;z-index:1;border-bottom:1px solid #d8d0c1}
button{font:inherit;font-size:14px;border:1px solid #b9ad99;background:#f7f4ec;color:#51463e;border-radius:6px;padding:3px 10px;cursor:pointer}
button[aria-pressed=true]{background:#51463e;color:#f7f4ec;border-color:#51463e}label{display:inline-flex;gap:6px;align-items:center;font-size:14px}
#info{font-size:14px;color:#6d6054;min-height:1.6em}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px;margin-top:10px}
figure{margin:0;background:#f4f1e9;border:1px solid #d8d0c1;border-radius:10px;overflow:hidden}figure[hidden]{display:none}
figcaption{display:flex;justify-content:space-between;gap:8px;padding:5px 10px;font-size:14px;border-bottom:1px solid #e2dbcd}
figcaption small{color:#8a7d6d}canvas{display:block;width:100%;height:320px;touch-action:none;cursor:grab}canvas:active{cursor:grabbing}
footer{margin-top:14px;font-size:12.5px;color:#7b6f60}input[type=range]{vertical-align:middle}
</style>
<main>
<h1>世界观动作 · 交互预览（第一批，待复查）</h1>
<p>使用游戏运行模型与动作修正资源（__REVISION__）。选择动作后自动播放，表情为该行为在游戏中显示的表情。拖动画面旋转，滚轮缩放。直接双击打开，不需要服务器。</p>
<div class="sticky">
<div class="bar" id="actions" role="toolbar" aria-label="动作"></div>
<div class="bar"><button type="button" id="play" aria-pressed="true">暂停</button>
<label>速度 <select id="speed"><option value=".25">0.25×</option><option value=".5">0.5×</option><option value="1" selected>1×</option><option value="2">2×</option></select></label>
<label>时间 <input id="time" type="range" min="0" max="1" step=".01" value="0"> <span id="clock">0.00 秒</span></label>
__VIEW_BUTTONS__
<label>缩放 <input id="zoom" type="range" min="60" max="260" value="100"></label>
<label><input id="sync" type="checkbox" checked> 同步旋转</label></div>
<div class="bar" id="models" aria-label="形态"></div>
<div id="info"></div>
</div>
<div class="grid" id="grid"></div>
<footer>动作源：standards/yukkuri-library/motions/variants/lore。正面视图中 +X 显示在右侧，与 Minecraft 一致。生成于 __DATE__。离线页面只供查看动作，不代表游戏运行验收；游戏中可用 <code>/yukkuri action &lt;目标&gt; &lt;动作&gt;</code> 查看。</footer>
</main>
<script>
const PANELS=__PANELS__,RECIPE=__RECIPE__,FACE=__FACE__,ORDER=__ORDER__;
__RUNTIME__
const $=id=>document.getElementById(id),grid=$('grid');let action='greet',time=0,playing=true,last=performance.now();
function decode(b,T){const s=atob(b),u=new Uint8Array(s.length);for(let i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return new T(u.buffer);}
const state=PANELS.map(p=>{
  const raw=decode(p.vertices,Int16Array),n=raw.length/8,rest=new Float32Array(raw.length);
  for(let i=0;i<n;i++){const o=i*8;for(let k=0;k<3;k++){rest[o+k]=raw[o+k]/1024;rest[o+5+k]=raw[o+5+k]/1024;}rest[o+3]=raw[o+3]/16384;rest[o+4]=raw[o+4]/16384;}
  const fig=document.createElement('figure');fig.innerHTML=`<figcaption><span>${p.label}</span><small></small></figcaption><canvas role="img" aria-label="${p.label}"></canvas>`;grid.append(fig);
  let y0=Infinity,y1=-Infinity,r=0;for(let i=0;i<n;i++){const o=i*8;y0=Math.min(y0,rest[o+1]);y1=Math.max(y1,rest[o+1]);r=Math.max(r,Math.hypot(rest[o],rest[o+2]));}
  return {p,rest,out:new Float32Array(rest),w:decode(p.weights,Uint16Array),q:decode(p.blend,Uint8Array),fig,canvas:fig.querySelector('canvas'),small:fig.querySelector('small'),
    az:.5,el:.18,frame:{centerY:(y0+y1)/2,half:(y1-y0)/2+1.5,r:r+1},textures:{},mats:p.bones.map(()=>new Float64Array(12))};
});
function sample(keys,t){const n=keys.length/4;if(t<=keys[0])return [keys[1],keys[2],keys[3]];
  for(let i=1;i<n;i++)if(t<=keys[i*4]){const a=(i-1)*4,b=i*4,q=keys[b]>keys[a]?(t-keys[a])/(keys[b]-keys[a]):0;return [0,1,2].map(k=>keys[a+1+k]+(keys[b+1+k]-keys[a+1+k])*q);}
  const a=(n-1)*4;return [keys[a+1],keys[a+2],keys[a+3]];}
function pose(s,name,t){
  const clip=s.p.clips[name],vals=s.p.bones.map(b=>b.slice(4,13));
  if(clip){const local=clip.loop?t%clip.d:Math.min(t,clip.d);for(const [bone,off,keys] of clip.tracks){const v=sample(keys,local);vals[bone][off]=v[0];vals[bone][off+1]=v[1];vals[bone][off+2]=v[2];}}
  s.p.bones.forEach((b,i)=>{const v=vals[i],px=b[1],py=b[2],pz=b[3];
    const [x,y,z]=[v[3],v[4],v[5]].map(a=>a*Math.PI/180),cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
    const R=[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx,sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx,-sy,cy*sx,cy*cx];
    const M=new Float64Array(12);for(let r=0;r<3;r++){for(let c=0;c<3;c++)M[r*4+c]=R[r*3+c]*v[6+c];M[r*4+3]=[px,py,pz][r]+v[r]-(M[r*4]*px+M[r*4+1]*py+M[r*4+2]*pz);}
    if(b[0]<0)s.mats[i]=M;else{const A=s.mats[b[0]],W=new Float64Array(12);
      for(let r=0;r<3;r++){for(let c=0;c<4;c++)W[r*4+c]=A[r*4]*M[c]+A[r*4+1]*M[4+c]+A[r*4+2]*M[8+c]+(c===3?A[r*4+3]:0);}s.mats[i]=W;}});
  const R=s.rest,O=s.out,n=R.length/8;
  for(let i=0;i<n;i++){const o=i*8,A=s.mats[s.w[i*2]],B=s.mats[s.w[i*2+1]],q=s.q[i]/255,x=R[o],y=R[o+1],z=R[o+2];
    for(let r=0;r<3;r++){const a=A[r*4]*x+A[r*4+1]*y+A[r*4+2]*z+A[r*4+3],b=B[r*4]*x+B[r*4+1]*y+B[r*4+2]*z+B[r*4+3];O[o+r]=a+(b-a)*q;}
    const nx=R[o+5],ny=R[o+6],nz=R[o+7];let mx=A[0]*nx+A[1]*ny+A[2]*nz,my=A[4]*nx+A[5]*ny+A[6]*nz,mz=A[8]*nx+A[9]*ny+A[10]*nz;const l=Math.hypot(mx,my,mz)||1;
    O[o+5]=mx/l;O[o+6]=my/l;O[o+7]=mz/l;}
}
function texture(s,face){const key=s.p.textures[face]?face:'neutral';if(s.textures[key]!==undefined)return s.textures[key];s.textures[key]=null;
  const img=new Image();img.onload=()=>{const c=document.createElement('canvas');c.width=c.height=s.p.textureSize;const x=c.getContext('2d');x.drawImage(img,0,0,c.width,c.height);s.textures[key]=x.getImageData(0,0,c.width,c.height).data;};img.src=s.p.textures[key];return null;}
function draw(s){
  if(s.fig.hidden)return;const has=!!s.p.clips[action];s.small.textContent=has?'':'无此动作，显示待机';
  pose(s,has?action:'idle',time);const tex=texture(s,FACE[action]||'neutral');if(!tex)return;
  const c=s.canvas,w=Math.max(1,Math.round(c.clientWidth)),h=Math.max(1,Math.round(c.clientHeight));if(c.width!==w)c.width=w;if(c.height!==h)c.height=h;
  const span=Math.max(s.frame.half,s.frame.r*h/w)*1.1*100/Number($('zoom').value);
  c.getContext('2d').putImageData(new ImageData(renderModel(s.out,tex,w,h,s.az,s.el,span,{centerY:s.frame.centerY,textureSize:s.p.textureSize}),w,h),0,0);
}
function duration(){const r=RECIPE[action];return r?r.duration:Math.max(...state.map(s=>s.p.clips[action]?.d||0),1);}
function info(){const r=RECIPE[action];$('info').textContent=r?`${r.label} · ${r.duration} 秒${r.loop?'循环':'单次'} · 依据：${r.lore} · 触发：${r.triggers.join('、')}`:`${EXTRA[action][0]} · 已有动作 · 触发：${EXTRA[action][1]}`;}
const EXTRA=__EXTRA__;
for(const name of ORDER){const b=document.createElement('button');b.type='button';b.textContent=(RECIPE[name]?.label||EXTRA[name][0]);b.dataset.action=name;
  b.onclick=()=>{action=name;time=0;document.querySelectorAll('[data-action]').forEach(o=>o.setAttribute('aria-pressed',String(o===b)));$('time').max=duration();info();};$('actions').append(b);}
state.forEach(s=>{const l=document.createElement('label');l.innerHTML=`<input type="checkbox" checked> ${s.p.label}`;l.firstChild.onchange=e=>{s.fig.hidden=!e.target.checked;};$('models').append(l);});
document.querySelector(`[data-action="${action}"]`).click();
$('play').onclick=()=>{playing=!playing;$('play').textContent=playing?'暂停':'播放';$('play').setAttribute('aria-pressed',String(playing));};
$('time').oninput=()=>{time=Number($('time').value);playing=false;$('play').textContent='播放';$('play').setAttribute('aria-pressed','false');};
document.querySelectorAll('[data-az]').forEach(b=>b.onclick=()=>state.forEach(s=>{s.az=Number(b.dataset.az);s.el=Number(b.dataset.el);}));
state.forEach(s=>{const pointers=new Map();
  s.canvas.addEventListener('pointerdown',e=>{pointers.set(e.pointerId,[e.clientX,e.clientY]);s.canvas.setPointerCapture(e.pointerId);});
  s.canvas.addEventListener('pointermove',e=>{if(!pointers.has(e.pointerId))return;const o=pointers.get(e.pointerId);pointers.set(e.pointerId,[e.clientX,e.clientY]);
    for(const t of $('sync').checked?state:[s]){t.az-=(e.clientX-o[0])*.009;t.el=Math.max(-1.1,Math.min(1.45,t.el+(e.clientY-o[1])*.009));}});
  for(const n of ['pointerup','pointercancel','lostpointercapture'])s.canvas.addEventListener(n,e=>pointers.delete(e.pointerId));
  s.canvas.addEventListener('wheel',e=>{e.preventDefault();$('zoom').value=String(Math.max(60,Math.min(260,Number($('zoom').value)*Math.exp(-e.deltaY*.001))));},{passive:false});});
function tick(now){const dt=Math.min(.1,(now-last)/1000);last=now;
  if(playing){time+=dt*Number($('speed').value);const d=duration(),loop=RECIPE[action]?.loop??true;if(time>d+(loop?0:.6))time=loop?time%d:0;$('time').value=Math.min(time,d);}
  $('clock').textContent=Math.min(time,duration()).toFixed(2)+' 秒';state.forEach(draw);requestAnimationFrame(tick);}
requestAnimationFrame(tick);
</script></html>
'''

VIEWS = [('正面', 0, .12), ('右前', .5, .18), ('右侧', 1.5707963, .05), ('左侧', -1.5707963, .05), ('背面', 3.1415927, .12)]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--out', default=str(P.OUT/'motions.html')); ap.add_argument('--models', default=','.join(P.MODELS))
    a = ap.parse_args()
    recipe = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
    corrections = json.loads((P.ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    order = list(recipe['clips'])+list(EXTRA)
    panels = [panel(m, corrections, order) for m in a.models.split(',')]
    face = dict(P.FACE, hop='happy', shell_retract='surprise')
    buttons = ''.join(f'<button type="button" data-az="{az}" data-el="{el}">{n}</button>' for n, az, el in VIEWS)
    html = (PAGE.replace('__REVISION__', corrections['revision']).replace('__DATE__', datetime.date.today().isoformat())
            .replace('__VIEW_BUTTONS__', buttons)
            .replace('__PANELS__', json.dumps(panels, ensure_ascii=False, separators=(',', ':')))
            .replace('__RECIPE__', json.dumps(recipe['clips'], ensure_ascii=False, separators=(',', ':')))
            .replace('__FACE__', json.dumps(face)).replace('__ORDER__', json.dumps(order))
            .replace('__EXTRA__', json.dumps({k: list(v) for k, v in EXTRA.items()}, ensure_ascii=False))
            .replace('__RUNTIME__', (LIB/'runtime/render.js').read_text(encoding='utf-8-sig')))
    Path(a.out).write_text(html, encoding='utf-8')
    print('wrote', a.out, f'{len(html)/1e6:.2f} MB', [p['triangles'] for p in panels])


if __name__ == '__main__':
    main()
