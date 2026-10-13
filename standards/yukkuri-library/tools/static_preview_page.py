"""Write a self-contained static HTML page comparing assembled models side by side.

Embeds the library's own runtime/render.js, the exported geometry (int16 / 1024, base64) and
neutral textures, so the page opens directly from disk without a server and uses the same
view convention as assembled/<name>/index.html. Models are shown in their rest pose (cube and
rounded-mesh exports alike); hidden default parts (puff, anatomy, contact) are omitted. Writing
the page does not start a browser, renderer or the project.

  python tools/static_preview_page.py previews/current/marisa-braid.html \
      --title "魔理沙单辫" "普通魔理沙=marisa" "灵梦（对照）=reimu"
"""
from pathlib import Path
import argparse, base64, datetime, hashlib, json, os, struct, sys

R = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(R), str(R/'ecology/house-yukkuri')]
from mesh_geometry import vertices  # cubes and the rounded meshes

def texture_file(out, name):
    """Neutral atlas next to the export, else the selected game texture."""
    for p in (out/f'{name}.png', out/'textures/neutral.png', R.parents[1]/f'src/main/resources/assets/mcyukkuri/textures/entity/{name}/neutral.png'):
        if p.exists(): return p
    raise FileNotFoundError(name)

def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))

def panel(label, source):
    """source is a library character or a .bbmodel path (relative to the library) with a sibling .rig.json."""
    if source.endswith('.bbmodel'):
        model = R/source; out, name = model.parent, model.stem; rig_path = out/f'{name}.rig.json'
        if not rig_path.exists(): rig_path = out/'animation-rig.json'
    else:
        out, name = R/'assembled'/source, source; rig_path = out/'animation-rig.json'
    bb = read(out/f'{name}.bbmodel'); rig = read(rig_path); size = bb['resolution']['width']
    keep = [(e, g) for e, g in zip(bb['elements'], rig['groups'])
            if not g.startswith(('puff_', 'anatomy_')) and 'contact_' not in g]
    flat = [v for e, _ in keep for v in vertices(e, size)]
    packed = struct.pack(f'<{len(flat)}h', *(max(-32768, min(32767, round(v*1024))) for v in flat))
    files = [out/f'{name}.bbmodel', rig_path, texture_file(out, name)]
    return dict(label=label, source=str(out.relative_to(R)/f'{name}.bbmodel'), vertices=base64.b64encode(packed).decode(),
                texture='data:image/png;base64,'+base64.b64encode(texture_file(out, name).read_bytes()).decode(),
                textureSize=size, cubes=len(keep),
                sha256={os.path.relpath(p, R): hashlib.sha256(p.read_bytes()).hexdigest()[:12] for p in files})

PAGE = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__ · 静态预览</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#eae7de;color:#51463e;font:15px/1.6 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
main{max-width:1240px;margin:auto;padding:18px}h1{font-size:22px;margin:0 0 4px}p{margin:4px 0 10px}
.bar{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:10px 0;position:sticky;top:0;background:#eae7de;padding:6px 0;z-index:1}
button{font:inherit;border:1px solid #b9ad99;background:#f7f4ec;color:#51463e;border-radius:6px;padding:4px 11px;cursor:pointer}
button[aria-pressed=true]{background:#51463e;color:#f7f4ec;border-color:#51463e}label{display:inline-flex;gap:6px;align-items:center}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(290px,1fr));gap:12px}
figure{margin:0;background:#f4f1e9;border:1px solid #d8d0c1;border-radius:10px;overflow:hidden}
figcaption{display:flex;justify-content:space-between;gap:8px;padding:6px 10px;font-size:14px;border-bottom:1px solid #e2dbcd}
figcaption small{color:#8a7d6d}canvas{display:block;width:100%;height:360px;touch-action:none;cursor:grab}canvas:active{cursor:grabbing}
footer{margin-top:14px;font-size:12.5px;color:#7b6f60}code{font-size:12px}
</style>
<main>
<h1>__TITLE__</h1>
<p>__NOTE__</p>
<div class="bar" role="toolbar" aria-label="视角">
__VIEW_BUTTONS__
<label>缩放 <input id="zoom" type="range" min="60" max="260" value="100"> <span id="zoomValue">100%</span></label>
<label><input id="sync" type="checkbox" checked> 同步旋转</label>
</div>
<div class="grid" id="grid"></div>
<footer>拖动画面旋转，滚轮或滑块缩放；视角与缩放默认对所有模型同步。正面视图中 +X 显示在右侧，与素材库完整预览和 Minecraft 一致。生成于 __DATE__，来源导出（SHA-256 前 12 位）：<span id="sources"></span>。本页只供造型查看，不代表浏览器、Blockbench 或游戏运行验收。</footer>
</main>
<script>
const PANELS=__PANELS__;
__RUNTIME__
const grid=document.getElementById('grid'),zoom=document.getElementById('zoom'),zoomValue=document.getElementById('zoomValue'),sync=document.getElementById('sync');
const views=[...document.querySelectorAll('[data-az]')];
const state=PANELS.map(p=>{
  const bytes=Uint8Array.from(atob(p.vertices),c=>c.charCodeAt(0)),dv=new DataView(bytes.buffer),raw=new Float32Array(bytes.length/2);
  for(let i=0;i<raw.length;i++)raw[i]=dv.getInt16(i*2,true)/1024;
  const fig=document.createElement('figure');
  fig.innerHTML=`<figcaption><span>${p.label}</span><small>${p.cubes} 个部件</small></figcaption><canvas role="img" aria-label="${p.label}"></canvas>`;
  grid.append(fig);
  return {p,raw,canvas:fig.querySelector('canvas'),az:0,el:.12,posed:null,frame:null,texture:null,pending:false};
});
document.getElementById('sources').textContent=PANELS.map(p=>Object.entries(p.sha256).map(([f,h])=>f+' '+h).join('，')).join('；');
function pose(s){
  s.posed=s.raw;
  if(!s.frame){let y0=Infinity,y1=-Infinity,r=0;for(let i=0;i<s.posed.length;i+=8){const x=s.posed[i],y=s.posed[i+1],z=s.posed[i+2];y0=Math.min(y0,y);y1=Math.max(y1,y);r=Math.max(r,Math.hypot(x,z));}s.frame={centerY:(y0+y1)/2,half:(y1-y0)/2,r};}
}
function draw(s){
  s.pending=false;if(!s.texture)return;
  const c=s.canvas,w=Math.max(1,Math.round(c.clientWidth)),h=Math.max(1,Math.round(c.clientHeight));
  if(c.width!==w)c.width=w;if(c.height!==h)c.height=h;
  const span=Math.max(s.frame.half,s.frame.r*h/w)*1.08*100/Number(zoom.value);
  c.getContext('2d').putImageData(new ImageData(renderModel(s.posed,s.texture,w,h,s.az,s.el,span,{centerY:s.frame.centerY,textureSize:s.p.textureSize}),w,h),0,0);
}
function redraw(s){if(!s.pending){s.pending=true;requestAnimationFrame(()=>draw(s));}}
function redrawAll(){state.forEach(redraw)}
function setZoom(v){zoom.value=String(Math.max(60,Math.min(260,Math.round(v))));zoomValue.textContent=zoom.value+'%';redrawAll();}
zoom.oninput=()=>setZoom(zoom.value);
views.forEach(b=>b.onclick=()=>{state.forEach(s=>{s.az=Number(b.dataset.az);s.el=Number(b.dataset.el);});views.forEach(o=>o.setAttribute('aria-pressed',String(o===b)));redrawAll();});
state.forEach(s=>{
  const pointers=new Map();
  s.canvas.addEventListener('pointerdown',e=>{pointers.set(e.pointerId,[e.clientX,e.clientY]);s.canvas.setPointerCapture(e.pointerId);});
  s.canvas.addEventListener('pointermove',e=>{
    if(!pointers.has(e.pointerId))return;const o=pointers.get(e.pointerId);pointers.set(e.pointerId,[e.clientX,e.clientY]);
    const targets=sync.checked?state:[s];
    for(const t of targets){t.az-=(e.clientX-o[0])*.009;t.el=Math.max(-1.1,Math.min(1.45,t.el+(e.clientY-o[1])*.009));redraw(t);}
    views.forEach(b=>b.setAttribute('aria-pressed','false'));
  });
  for(const n of ['pointerup','pointercancel','lostpointercapture'])s.canvas.addEventListener(n,e=>pointers.delete(e.pointerId));
  s.canvas.addEventListener('wheel',e=>{e.preventDefault();setZoom(Number(zoom.value)*Math.exp(-e.deltaY*.001));},{passive:false});
  new ResizeObserver(()=>redraw(s)).observe(s.canvas);
  pose(s);
  const img=new Image();
  img.onload=()=>{const b=document.createElement('canvas');b.width=b.height=s.p.textureSize;const x=b.getContext('2d');x.drawImage(img,0,0);s.texture=x.getImageData(0,0,b.width,b.height).data;redraw(s);};
  img.src=s.p.texture;
});
</script></html>
'''

VIEWS = [('正面', 0, .12), ('右前', .62, .2), ('右侧', 1.5707963, .05), ('左侧', -1.5707963, .05), ('背面', 3.1415927, .12), ('俯视', .35, .95)]

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('out'); ap.add_argument('panels', nargs='+', help='label=character or label=path/to/model.bbmodel')
    ap.add_argument('--title', default='模型静态预览'); ap.add_argument('--note', default='')
    a = ap.parse_args()
    panels = [panel(*p.split('=', 1)) for p in a.panels]
    runtime = (R/'runtime/render.js').read_text(encoding='utf-8-sig')
    buttons = '\n'.join(f'<button type="button" data-az="{az}" data-el="{el}" aria-pressed="{str(i == 0).lower()}">{n}</button>'
                        for i, (n, az, el) in enumerate(VIEWS))
    html = (PAGE.replace('__TITLE__', a.title).replace('__NOTE__', a.note).replace('__VIEW_BUTTONS__', buttons)
            .replace('__DATE__', datetime.date.today().isoformat())
            .replace('__PANELS__', json.dumps(panels, ensure_ascii=False, separators=(',', ':')))
            .replace('__RUNTIME__', runtime))
    (R/a.out).write_text(html, encoding='utf-8')
    print('wrote', a.out, f'{len(html)/1e6:.2f} MB', len(panels), 'panels')

if __name__ == '__main__':
    main()
